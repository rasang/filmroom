// Filmroom bridge to the Adobe DNG SDK.
//
// This product includes DNG technology under license by Adobe.
//
// Every DNG is read, linearized, demosaiced and colour-rendered by the
// unmodified Adobe DNG SDK (third-party/adobe-dng-sdk). This file only adapts
// the SDK to Filmroom's private output contract:
//   * headerless little-endian float32 RGB, final orientation and default crop,
//   * scene-linear sRGB primaries (D65), BaselineExposure applied once,
//   * no tone curve and no shadow ramp (the film engine owns tone rendering).
//
// Large SDK pixel buffers beyond the caller's memory budget are backed by
// unlinked, fully pre-allocated files in the private cache directory so big
// DNGs do not exhaust RAM. C++ exceptions never cross the C ABI.

#include "dng_1d_function.h"
#include "dng_abort_sniffer.h"
#include "dng_area_task.h"
#include "dng_bottlenecks.h"
#include "dng_camera_profile.h"
#include "dng_color_space.h"
#include "dng_exceptions.h"
#include "dng_file_stream.h"
#include "dng_gain_map.h"
#include "dng_host.h"
#include "dng_ifd.h"
#include "dng_image.h"
#include "dng_info.h"
#include "dng_lens_correction.h"
#include "dng_linearization_info.h"
#include "dng_memory.h"
#include "dng_memory_stream.h"
#include "dng_negative.h"
#include "dng_opcode_list.h"
#include "dng_opcodes.h"
#include "dng_orientation.h"
#include "dng_pixel_buffer.h"
#include "dng_render.h"
#include "dng_resample.h"
#include "dng_safe_arithmetic.h"
#include "dng_simple_image.h"
#include "dng_tag_values.h"
#include "dng_utils.h"
#include "dng_xmp_sdk.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

#define FILM_EXPORT extern "C" __attribute__((visibility("default")))

extern "C" {

// Field order and types must match `Info` in src/dng_sdk.rs.
struct FilmDngInfo {
	uint32_t encoded_width;
	uint32_t encoded_height;
	uint32_t width;   // final, oriented output
	uint32_t height;
	uint32_t active_x, active_y, active_w, active_h;
	uint32_t crop_x, crop_y, crop_w, crop_h;
	uint32_t orientation;      // TIFF 1..8
	uint32_t color_planes;     // 1 = monochrome, 3 = RGB/CFA, 4 = CMYG
	uint32_t is_cfa;
	uint32_t compression;      // TIFF Compression of the main raw IFD
	uint32_t bits_per_sample;
	uint32_t sample_format;    // 1 = unsigned, 3 = float
	uint32_t has_forward_matrix;
	uint32_t dng_version;      // 0xMMmmrrpp
	uint32_t optical_flags;    // bitmask of OpticalFlags below
	double baseline_exposure;  // total EV applied by rendering
	uint64_t heap_peak_bytes;
	uint64_t disk_peak_bytes;
	char message[160];
};

// Optional Stage 3 Lensfun callbacks for native-firmware camera CFA DNGs that
// carry no embedded opcode contract. Executed in demosaiced linear camera RGB
// before dng_render applies color matrices, ProfileHueSatMap, crop, or rotation.
struct FilmLensfunHooks {
	const void *ctx;
	uint32_t has_vignette;
	uint32_t has_warp;
	int32_t (*vignette_row)(const void *ctx, uint32_t row, uint32_t col0, uint32_t cols, float *gains_out);
	int32_t (*warp_row)(const void *ctx, uint32_t crop_row, uint32_t crop_col0, uint32_t cols, double *src_xy_out);
};

}  // extern "C"

namespace {

enum Status : int32_t {
	kOk = 0,
	kCancelled = 1,
	kInvalidInput = 2,
	kUnsupported = 3,
	kMemoryBudget = 4,
	kDiskBudget = 5,
	kIoError = 6,
	kNoSpace = 7,
	kInternal = 8,
	kUnsupportedOpcode = 9,
};

// Must match FLAG_* constants in src/dng_sdk.rs.
enum OpticalFlags : uint32_t {
	kOptHasOpcodeTag        = 1u << 0,
	kOptEmbeddedDistortion  = 1u << 1,
	kOptEmbeddedTca         = 1u << 2,
	kOptEmbeddedVignetting  = 1u << 3,
	kOptOptionalSkipped     = 1u << 4,
	kOptStrippedToneCurve   = 1u << 5,
	kOptHasProfileGainTable = 1u << 6,
	kOptNonUnitScale        = 1u << 7,
	kOptStage1Warp          = 1u << 8,
	kOptStage2Warp          = 1u << 9,
	kOptStage3Warp          = 1u << 10,
};

struct CallbackError {
	int32_t status;
};

constexpr uint64_t kMiB = 1024ull * 1024ull;
// Blocks below this size always use the heap (tables, tile buffers, ...).
constexpr uint64_t kSpillThreshold = 4 * kMiB;

std::once_flag gXmpInit;

class CancelSniffer final : public dng_abort_sniffer {
public:
	explicit CancelSniffer(const uint8_t *flag) : fFlag(flag) {}
	bool ThreadSafe() const override { return true; }

protected:
	void Sniff() override {
		if (fFlag && __atomic_load_n(fFlag, __ATOMIC_RELAXED)) {
			ThrowUserCanceled();
		}
	}

private:
	const uint8_t *fFlag;
};

class BudgetAllocator;

// Heap block whose size is accounted against the memory budget.
class CountedBlock final : public dng_memory_block {
public:
	CountedBlock(dng_memory_block *inner, std::atomic<uint64_t> &live, uint64_t bytes)
		: dng_memory_block(inner->LogicalSize()), fInner(inner), fLive(live), fBytes(bytes) {
		SetBuffer(fInner->Buffer());
	}
	~CountedBlock() override {
		delete fInner;
		fLive.fetch_sub(fBytes);
	}

private:
	dng_memory_block *fInner;
	std::atomic<uint64_t> &fLive;
	uint64_t fBytes;
};

// File-backed block: an unlinked, fully allocated cache file mapped shared, so
// dirty pages are reclaimable by the kernel instead of pinning anonymous RAM.
class SpillBlock final : public dng_memory_block {
public:
	SpillBlock(uint32 logicalSize, void *map, size_t mapBytes, std::atomic<uint64_t> &live)
		: dng_memory_block(logicalSize), fMap(map), fMapBytes(mapBytes), fLive(live) {
		SetBuffer(fMap);
	}
	~SpillBlock() override {
		munmap(fMap, fMapBytes);
		fLive.fetch_sub(fMapBytes);
	}
	static uint32 Physical(uint32 logical) {
		struct Probe : dng_memory_block {
			explicit Probe(uint32 n) : dng_memory_block(n) {}
			uint32 Size() { return PhysicalSize(); }
		};
		return Probe(logical).Size();
	}

private:
	void *fMap;
	size_t fMapBytes;
	std::atomic<uint64_t> &fLive;
};

class BudgetAllocator final : public dng_memory_allocator {
public:
	BudgetAllocator(std::string dir, uint64_t heapBudget, uint64_t diskBudget)
		: fDir(std::move(dir)), fHeapBudget(heapBudget), fDiskBudget(diskBudget) {}

	dng_memory_block *Allocate(uint32 size) override {
		const uint64_t bytes = size;
		if (bytes < kSpillThreshold) {
			return dng_memory_allocator::Allocate(size);
		}
		const uint64_t heap = fHeapLive.fetch_add(bytes) + bytes;
		if (heap <= fHeapBudget) {
			try {
				dng_memory_block *inner = dng_memory_allocator::Allocate(size);
				NotePeak(fHeapPeak, heap);
				return new CountedBlock(inner, fHeapLive, bytes);
			} catch (...) {
				fHeapLive.fetch_sub(bytes);
				throw;
			}
		}
		fHeapLive.fetch_sub(bytes);
		return Spill(size);
	}

	bool diskFailed() const { return fDiskFailed.load(); }
	bool noSpace() const { return fNoSpace.load(); }
	uint64_t heapPeak() const { return fHeapPeak.load(); }
	uint64_t diskPeak() const { return fDiskPeak.load(); }

private:
	static void NotePeak(std::atomic<uint64_t> &peak, uint64_t value) {
		uint64_t seen = peak.load();
		while (value > seen && !peak.compare_exchange_weak(seen, value)) {
		}
	}

	dng_memory_block *Spill(uint32 size) {
		const size_t mapBytes = SpillBlock::Physical(size);
		const uint64_t disk = fDiskLive.fetch_add(mapBytes) + mapBytes;
		if (disk > fDiskBudget) {
			fDiskLive.fetch_sub(mapBytes);
			fDiskFailed = true;
			ThrowMemoryFull("DNG working set exceeds disk budget");
		}
		int fd = -1;
#ifdef O_TMPFILE
		fd = open(fDir.c_str(), O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
#endif
		if (fd < 0) {
			std::string name = fDir + "/.film-dng-spill-XXXXXX";
			std::vector<char> path(name.begin(), name.end());
			path.push_back('\0');
			fd = mkstemp(path.data());
			if (fd >= 0) {
				unlink(path.data());
			}
		}
		if (fd < 0) {
			fDiskLive.fetch_sub(mapBytes);
			fDiskFailed = true;
			ThrowMemoryFull("cannot create DNG spill file");
		}
		// Reserve real blocks first: writing a sparse shared mapping on a full
		// disk would raise SIGBUS instead of a recoverable error.
		const int rc = posix_fallocate(fd, 0, (off_t) mapBytes);
		if (rc != 0) {
			close(fd);
			fDiskLive.fetch_sub(mapBytes);
			fDiskFailed = true;
			if (rc == ENOSPC || rc == EDQUOT) {
				fNoSpace = true;
			}
			ThrowMemoryFull("cannot reserve DNG spill file");
		}
		void *map = mmap(nullptr, mapBytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
		close(fd);
		if (map == MAP_FAILED) {
			fDiskLive.fetch_sub(mapBytes);
			ThrowMemoryFull("cannot map DNG spill file");
		}
		NotePeak(fDiskPeak, disk);
		return new SpillBlock(size, map, mapBytes, fDiskLive);
	}

	std::string fDir;
	uint64_t fHeapBudget;
	uint64_t fDiskBudget;
	std::atomic<uint64_t> fHeapLive{0};
	std::atomic<uint64_t> fDiskLive{0};
	std::atomic<uint64_t> fHeapPeak{0};
	std::atomic<uint64_t> fDiskPeak{0};
	std::atomic<bool> fDiskFailed{false};
	std::atomic<bool> fNoSpace{false};
};

class MappedOutput {
public:
	MappedOutput() = default;
	MappedOutput(const MappedOutput &) = delete;
	MappedOutput &operator=(const MappedOutput &) = delete;
	~MappedOutput() { Reset(); }

	void *Map(int fd, size_t bytes) {
		Reset();
		if (bytes == 0) return nullptr;
		void *p = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
		if (p == MAP_FAILED) return nullptr;
		fMap = p;
		fBytes = bytes;
		return fMap;
	}

	void Reset() {
		if (fMap) {
			munmap(fMap, fBytes);
			fMap = nullptr;
			fBytes = 0;
		}
	}

private:
	void *fMap = nullptr;
	size_t fBytes = 0;
};

// In-place Stage 3 vignetting task matching dng_opcode_GainMap's black-pedestal
// handling: un-offset Stage3BlackLevel, multiply by Lensfun radial gain, re-offset.
class LensfunVignetteTask final : public dng_area_task {
public:
	LensfunVignetteTask(dng_image &image, const dng_negative &negative, const FilmLensfunHooks &hooks)
		: dng_area_task("LensfunVignetteTask"),
		  fImage(image),
		  fBounds(image.Bounds()),
		  fBlackLevel(negative.Stage3BlackLevel()),
		  fHooks(hooks) {
		fMaxTileSize = dng_point(64, 1024);
	}

	void Start(uint32 threadCount, const dng_rect & /* dstArea */, const dng_point &tileSize,
			   dng_memory_allocator *allocator, dng_abort_sniffer * /* sniffer */) override {
		const uint32 bufBytes = ComputeBufferSize(ttFloat, tileSize, fImage.Planes(), padSIMDBytes);
		for (uint32 i = 0; i < threadCount; i++) {
			fBuffer[i].Reset(allocator->Allocate(bufBytes));
			fGains[i].assign((size_t) tileSize.h, 1.0f);
		}
	}

	void Process(uint32 threadIndex, const dng_rect &tile, dng_abort_sniffer *sniffer) override {
		const uint32 planes = fImage.Planes();
		dng_pixel_buffer buffer(tile, 0, planes, ttFloat, pcPlanar, fBuffer[threadIndex]->Buffer());
		fImage.Get(buffer);

		const real32 blackOffset = fBlackLevel != 0 ? ((real32) fBlackLevel) / 65535.0f : 0.0f;
		const real32 blackScale = 1.0f - blackOffset;
		const real32 invBlackScale = blackScale > 0.0f ? 1.0f / blackScale : 0.0f;
		const uint32 cols = (uint32) tile.W();
		float *gains = fGains[threadIndex].data();

		for (int32 row = tile.t; row < tile.b; row++) {
			dng_abort_sniffer::SniffForAbort(sniffer);
			const int32_t rc = fHooks.vignette_row(
				fHooks.ctx, (uint32_t) (row - fBounds.t), (uint32_t) (tile.l - fBounds.l), cols, gains);
			if (rc != kOk) {
				if (rc == kCancelled) ThrowUserCanceled();
				throw CallbackError{rc};
			}
			for (uint32 plane = 0; plane < planes; plane++) {
				real32 *ptr = buffer.DirtyPixel_real32(row, tile.l, plane);
				if (fBlackLevel != 0) {
					for (uint32 c = 0; c < cols; c++) {
						real32 v = (ptr[c] - blackOffset) * invBlackScale;
						v = std::min<real32>(v * gains[c], 1.0f);
						ptr[c] = Pin_real32(v * blackScale + blackOffset);
					}
				} else {
					for (uint32 c = 0; c < cols; c++) {
						ptr[c] = Pin_real32(ptr[c] * gains[c]);
					}
				}
			}
		}
		fImage.Put(buffer);
	}

private:
	dng_image &fImage;
	dng_rect fBounds;
	uint16 fBlackLevel;
	const FilmLensfunHooks &fHooks;
	AutoPtr<dng_memory_block> fBuffer[kMaxMPThreads];
	std::vector<float> fGains[kMaxMPThreads];
};

// Stage 3 composite distortion + lateral CA resampling task using the SDK's
// 32x32-phase Catmull-Rom (A = -0.75) bicubic table over certified 128x32 tiles.
class LensfunWarpTask final : public dng_area_task {
public:
	LensfunWarpTask(const dng_image &srcImage, dng_image &dstImage,
					const dng_rect &cropArea, const FilmLensfunHooks &hooks)
		: dng_area_task("LensfunWarpTask"),
		  fSrcImage(srcImage),
		  fDstImage(dstImage),
		  fCropArea(cropArea),
		  fHooks(hooks) {
		fMinTaskArea = 32 * 128;
		fMaxTileSize = dng_point(32, 128);
	}

	void Initialize(dng_host &host) {
		fWeights.Initialize(dng_resample_bicubic::Get(), host.Allocator());
	}

	void Start(uint32 threadCount, const dng_rect & /* dstArea */, const dng_point &tileSize,
			   dng_memory_allocator *allocator, dng_abort_sniffer * /* sniffer */) override {
		fAllocator = allocator;
		const uint32 dstBytes = ComputeBufferSize(ttFloat, tileSize, 3, padSIMDBytes);
		for (uint32 i = 0; i < threadCount; i++) {
			fDstBuffer[i].Reset(allocator->Allocate(dstBytes));
			DoZeroBytes(fDstBuffer[i]->Buffer(), fDstBuffer[i]->LogicalSize());
			fCoords[i].assign((size_t) tileSize.v * (size_t) tileSize.h * 6, 0.0);
		}
	}

	void Process(uint32 threadIndex, const dng_rect &dstTile, dng_abort_sniffer *sniffer) override {
		const uint32 rows = (uint32) dstTile.H();
		const uint32 cols = (uint32) dstTile.W();
		double *coords = fCoords[threadIndex].data();
		const dng_rect srcBounds = fSrcImage.Bounds();
		const double minH = (double) srcBounds.l;
		const double maxH = (double) (srcBounds.r - 1);
		const double minV = (double) srcBounds.t;
		const double maxV = (double) (srcBounds.b - 1);

		int32 xMin = INT32_MAX;
		int32 xMax = INT32_MIN;
		int32 yMin = INT32_MAX;
		int32 yMax = INT32_MIN;

		for (uint32 r = 0; r < rows; r++) {
			dng_abort_sniffer::SniffForAbort(sniffer);
			double *rowCoords = coords + (size_t) r * cols * 6;
			const int32_t rc = fHooks.warp_row(
				fHooks.ctx,
				(uint32_t) (dstTile.t + (int32) r - fCropArea.t),
				(uint32_t) (dstTile.l - fCropArea.l),
				cols,
				rowCoords);
			if (rc != kOk) {
				if (rc == kCancelled) ThrowUserCanceled();
				throw CallbackError{rc};
			}
			for (uint32 i = 0; i < cols * 3; i++) {
				const double sx = std::clamp(rowCoords[i * 2 + 0] + minH, minH, maxH);
				const double sy = std::clamp(rowCoords[i * 2 + 1] + minV, minV, maxV);
				rowCoords[i * 2 + 0] = sx;
				rowCoords[i * 2 + 1] = sy;
				xMin = std::min(xMin, ConvertDoubleToInt32(std::floor(sx)));
				xMax = std::max(xMax, ConvertDoubleToInt32(std::ceil(sx)));
				yMin = std::min(yMin, ConvertDoubleToInt32(std::floor(sy)));
				yMax = std::max(yMax, ConvertDoubleToInt32(std::ceil(sy)));
			}
		}

		const int32 wCount = (int32) fWeights.Width();
		const int32 offset = fWeights.Offset();
		const dng_rect srcArea(
			SafeInt32Add(yMin, offset),
			SafeInt32Add(xMin, offset),
			SafeInt32Add(SafeInt32Add(yMax, offset), wCount + 1),
			SafeInt32Add(SafeInt32Add(xMax, offset), wCount + 1));

		const uint32 srcBytes = ComputeBufferSize(ttFloat, srcArea.Size(), 3, padSIMDBytes);
		// Certified Lensfun windows fit within 8 MiB (+ 4-tap border & SIMD row padding).
		if (srcBytes > 10 * kMiB) {
			ThrowMemoryFull("Lensfun source window exceeds certified tile cap");
		}
		if (!fSrcBuffer[threadIndex].Get() || fSrcBuffer[threadIndex]->LogicalSize() < srcBytes) {
			fSrcBuffer[threadIndex].Reset(fAllocator->Allocate(srcBytes));
			DoZeroBytes(fSrcBuffer[threadIndex]->Buffer(), fSrcBuffer[threadIndex]->LogicalSize());
		}

		dng_pixel_buffer srcBuffer(srcArea, 0, 3, ttFloat, pcPlanar, fSrcBuffer[threadIndex]->Buffer());
		fSrcImage.Get(srcBuffer, dng_image::edge_repeat);
		dng_pixel_buffer dstBuffer(dstTile, 0, 3, ttFloat, pcPlanar, fDstBuffer[threadIndex]->Buffer());

		const int32 srcRowStep = (int32) srcBuffer.RowStep();
		const int32 hMin = srcArea.l;
		const int32 hMax = srcArea.r - wCount;
		const int32 vMin = srcArea.t;
		const int32 vMax = srcArea.b - wCount;
		const real64 subsamples = (real64) kResampleSubsampleCount2D;
		const int32 maxPhase = (int32) kResampleSubsampleCount2D - 1;

		for (uint32 plane = 0; plane < 3; plane++) {
			real32 *dPtr = dstBuffer.DirtyPixel_real32(dstTile.t, dstTile.l, plane);
			for (uint32 r = 0; r < rows; r++) {
				const double *rowCoords = coords + (size_t) r * cols * 6;
				for (uint32 c = 0; c < cols; c++) {
					const double sx = rowCoords[(c * 3 + plane) * 2 + 0];
					const double sy = rowCoords[(c * 3 + plane) * 2 + 1];
					const int32 ix = ConvertDoubleToInt32(std::floor(sx));
					const int32 iy = ConvertDoubleToInt32(std::floor(sy));
					int32 fh = Pin_int32(0, ConvertDoubleToInt32((sx - (double) ix) * subsamples), maxPhase);
					int32 fv = Pin_int32(0, ConvertDoubleToInt32((sy - (double) iy) * subsamples), maxPhase);
					int32 tapH = ix + offset;
					int32 tapV = iy + offset;
					if (tapH < hMin) { tapH = hMin; fh = 0; }
					else if (tapH > hMax) { tapH = hMax; fh = 0; }
					if (tapV < vMin) { tapV = vMin; fv = 0; }
					else if (tapV > vMax) { tapV = vMax; fv = 0; }

					const real32 *w = fWeights.Weights32(dng_point(fv, fh));
					const real32 *s = srcBuffer.ConstPixel_real32(tapV, tapH, plane);
					real32 total = 0.0f;
					for (int32 i = 0; i < wCount; i++) {
						for (int32 j = 0; j < wCount; j++) {
							total += w[j] * s[j];
						}
						w += wCount;
						s += srcRowStep;
					}
					dPtr[c] = Pin_real32(total);
				}
				dPtr += dstBuffer.RowStep();
			}
		}

		fDstImage.Put(dstBuffer);
	}

private:
	const dng_image &fSrcImage;
	dng_image &fDstImage;
	dng_rect fCropArea;
	const FilmLensfunHooks &fHooks;
	dng_resample_weights_2d fWeights;
	dng_memory_allocator *fAllocator = nullptr;
	AutoPtr<dng_memory_block> fSrcBuffer[kMaxMPThreads];
	AutoPtr<dng_memory_block> fDstBuffer[kMaxMPThreads];
	std::vector<double> fCoords[kMaxMPThreads];
};

bool IsLensProfileOpcode(const dng_opcode &op) {
	switch (op.OpcodeID()) {
	case dngOpcode_WarpRectilinear:
	case dngOpcode_WarpRectilinear2:
	case dngOpcode_WarpFisheye:
	case dngOpcode_FixVignetteRadial:
	case dngOpcode_GainMap:
		return true;
	default:
		return false;
	}
}

// dng_host with a small fixed worker pool for area tasks (the SDK default is
// single-threaded), Stage 3 Lensfun hook execution, and optional zero-copy
// rendering directly into the pre-allocated output file mapping.
class FilmHost final : public dng_host {
public:
	FilmHost(dng_memory_allocator *allocator, dng_abort_sniffer *sniffer, uint32 threads)
		: dng_host(allocator, sniffer), fThreads(std::max<uint32>(1, threads)) {}

	void SetApplyProfileCorrections(bool apply) { fApplyProfileCorrections = apply; }

	void ArmLensfun(const FilmLensfunHooks *hooks) { fLensfun = hooks; }

	void ArmDirectOutput(void *map, uint32 outW, uint32 outH,
						 const dng_orientation &orientation, uint32 skipCalls) {
		fDirectMap = map;
		fDirectW = outW;
		fDirectH = outH;
		fDirectOrientation = orientation;
		fDirectSkip = skipCalls;
		fDirectUsed = false;
	}

	bool DirectOutputUsed() const { return fDirectUsed; }

	void ApplyOpcodeList(dng_opcode_list &list, dng_negative &negative,
						 AutoPtr<dng_image> &image) override {
		if (!fApplyProfileCorrections) {
			for (uint32 i = list.Count(); i > 0; i--) {
				if (IsLensProfileOpcode(list.Entry(i - 1))) {
					list.Remove(i - 1);
				}
			}
		}
		dng_host::ApplyOpcodeList(list, negative, image);
		// BuildStage3Image invokes ApplyOpcodeList on fStage3Image immediately
		// after demosaicing and releasing fStage2Image.
		if (!fLensfun || !image.Get() || image.Get() != negative.Stage3Image()) {
			return;
		}
		if (image->Planes() != 3 || list.Count() != 0) {
			ThrowBadFormat("Stage 3 Lensfun requires 3-plane camera RGB with empty OpcodeList3");
		}
		const dng_rect srcBounds = image->Bounds();
		const dng_rect cropArea = negative.DefaultCropArea();
		if ((cropArea & srcBounds) != cropArea || cropArea.W() < 5 || cropArea.H() < 5) {
			ThrowBadFormat("invalid DefaultCropArea for Stage 3 Lensfun");
		}
		if (fLensfun->has_vignette && fLensfun->vignette_row) {
			LensfunVignetteTask task(*image, negative, *fLensfun);
			PerformAreaTask(task, srcBounds, nullptr);
		}
		if (fLensfun->has_warp && fLensfun->warp_row) {
			AutoPtr<dng_image> dstImage(Make_dng_image(srcBounds, 3, image->PixelType()));
			if (cropArea != srcBounds) {
				if (cropArea.t > srcBounds.t) {
					dstImage->CopyArea(*image, dng_rect(srcBounds.t, srcBounds.l, cropArea.t, srcBounds.r), 0, 3);
				}
				if (cropArea.b < srcBounds.b) {
					dstImage->CopyArea(*image, dng_rect(cropArea.b, srcBounds.l, srcBounds.b, srcBounds.r), 0, 3);
				}
				if (cropArea.l > srcBounds.l) {
					dstImage->CopyArea(*image, dng_rect(cropArea.t, srcBounds.l, cropArea.b, cropArea.l), 0, 3);
				}
				if (cropArea.r < srcBounds.r) {
					dstImage->CopyArea(*image, dng_rect(cropArea.t, cropArea.r, cropArea.b, srcBounds.r), 0, 3);
				}
			}
			LensfunWarpTask task(*image, *dstImage, cropArea, *fLensfun);
			task.Initialize(*this);
			PerformAreaTask(task, cropArea, nullptr);
			image.Reset(dstImage.Release());
		}
	}

	dng_image *Make_dng_image(const dng_rect &bounds, uint32 planes, uint32 pixelType) override {
		if (fDirectMap) {
			if (fDirectSkip > 0) {
				fDirectSkip--;
			} else if (planes == 3 && pixelType == ttFloat) {
				void *map = fDirectMap;
				fDirectMap = nullptr;
				dng_pixel_buffer finalBuf(dng_rect(fDirectH, fDirectW), 0, 3, ttFloat,
										  pcInterleaved, map);
				AutoPtr<dng_simple_image> view(new dng_simple_image(finalBuf, Allocator()));
				view->Rotate(-fDirectOrientation);
				if (view->Bounds().Size() == bounds.Size()) {
					fDirectUsed = true;
					return view.Release();
				}
			}
		}
		return dng_host::Make_dng_image(bounds, planes, pixelType);
	}

	uint32 PerformAreaTaskThreads() override { return fThreads; }

	void PerformAreaTask(dng_area_task &task, const dng_rect &area,
						 dng_area_task_progress *progress) override {
		const dng_point tileSize = task.FindTileSize(area);
		uint32 threads = std::min(fThreads, task.MaxThreads());
		const uint32 tileRows = area.H() == 0 ? 0 : (area.H() + tileSize.v - 1) / tileSize.v;
		threads = std::min(threads, tileRows);
		if (threads <= 1 || (uint64_t) area.W() * area.H() < 2ull * task.MinTaskArea()) {
			dng_area_task::Perform(task, area, &Allocator(), Sniffer(), progress);
			return;
		}
		task.Start(threads, area, tileSize, &Allocator(), Sniffer());
		std::vector<std::exception_ptr> errors(threads);
		std::vector<std::thread> workers;
		workers.reserve(threads);
		for (uint32 i = 0; i < threads; i++) {
			dng_rect band = area;
			band.t = area.t + (int32) ((uint64_t) tileRows * i / threads) * tileSize.v;
			band.b = std::min<int32>(area.b, area.t + (int32) ((uint64_t) tileRows * (i + 1) / threads) * tileSize.v);
			workers.emplace_back([&, i, band] {
				try {
					task.ProcessOnThread(i, band, tileSize, Sniffer(), nullptr);
				} catch (...) {
					errors[i] = std::current_exception();
				}
			});
		}
		for (auto &w : workers) {
			w.join();
		}
		task.Finish(threads);
		for (auto &e : errors) {
			if (e) {
				std::rethrow_exception(e);
			}
		}
	}

private:
	uint32 fThreads;
	bool fApplyProfileCorrections = true;
	const FilmLensfunHooks *fLensfun = nullptr;
	void *fDirectMap = nullptr;
	uint32 fDirectW = 0;
	uint32 fDirectH = 0;
	dng_orientation fDirectOrientation;
	uint32 fDirectSkip = 0;
	bool fDirectUsed = false;
};

void SetMessage(FilmDngInfo *info, const char *text) {
	if (!info) return;
	std::snprintf(info->message, sizeof(info->message), "%s", text ? text : "");
}

int32_t MapDngError(dng_error_code code, const BudgetAllocator *alloc) {
	switch (code) {
	case dng_error_user_canceled:
		return kCancelled;
	case dng_error_memory:
		if (alloc && alloc->noSpace()) return kNoSpace;
		if (alloc && alloc->diskFailed()) return kDiskBudget;
		return kMemoryBudget;
	case dng_error_bad_format:
	case dng_error_end_of_file:
	case dng_error_file_is_damaged:
	case dng_error_matrix_math:
	case dng_error_overflow:
	case dng_error_jxl_decoder:
		return kInvalidInput;
	case dng_error_open_file:
	case dng_error_read_file:
	case dng_error_write_file:
		return kIoError;
	case dng_error_unsupported_dng:
	case dng_error_not_yet_implemented:
		return kUnsupported;
	default:
		return kInternal;
	}
}

bool RectilinearHasDistinctPlanes(const dng_warp_params_rectilinear &p) {
	if (p.fPlanes <= 1) return false;
	for (uint32 plane = 1; plane < p.fPlanes; plane++) {
		for (uint32 i = 0; i < dng_warp_params_radial::kMaxTerms; i++) {
			if (p.fRadParams.fData[plane][i] != p.fRadParams.fData[0][i]) return true;
		}
		if (p.fRadParams.fValidRange[plane][0] != p.fRadParams.fValidRange[0][0] ||
			p.fRadParams.fValidRange[plane][1] != p.fRadParams.fValidRange[0][1]) {
			return true;
		}
		if (p.fTanParams[plane].Count() != p.fTanParams[0].Count()) return true;
		for (uint32 i = 0; i < p.fTanParams[plane].Count(); i++) {
			if (p.fTanParams[plane][i] != p.fTanParams[0][i]) return true;
		}
	}
	return false;
}

bool FisheyeHasDistinctPlanes(dng_host &host, const dng_opcode_WarpFisheye &op) {
	dng_memory_stream mem(host.Allocator());
	mem.SetBigEndian();
	op.PutData(mem);
	mem.SetReadPosition(0);
	const uint32 bytes = mem.Get_uint32();
	const uint32 planes = mem.Get_uint32();
	if (planes <= 1 || bytes != 4 + 32 * planes + 16) return false;
	double ref[4];
	for (uint32 i = 0; i < 4; i++) ref[i] = mem.Get_real64();
	for (uint32 plane = 1; plane < planes; plane++) {
		for (uint32 i = 0; i < 4; i++) {
			if (mem.Get_real64() != ref[i]) return true;
		}
	}
	return false;
}

bool GainMapHasEffect(const dng_gain_map &gm) {
	const uint32 rows = (uint32) std::max<int32>(0, gm.Points().v);
	const uint32 cols = (uint32) std::max<int32>(0, gm.Points().h);
	const uint32 planes = gm.Planes();
	for (uint32 r = 0; r < rows; r++) {
		for (uint32 c = 0; c < cols; c++) {
			for (uint32 p = 0; p < planes; p++) {
				if (gm.Entry(r, c, p) != 1.0f) return true;
			}
		}
	}
	return false;
}

uint32_t StageWarpFlag(uint32 stage) {
	if (stage == 1) return kOptStage1Warp;
	if (stage == 2) return kOptStage2Warp;
	if (stage == 3) return kOptStage3Warp;
	return 0;
}

void InspectOpcodeList(dng_host &host, dng_stream &stream, const dng_negative &negative,
					   uint32 stage, uint32 byteCount, uint64 streamOffset,
					   bool applyProfileCorrections, uint32_t &flags) {
	if (byteCount == 0) return;
	flags |= kOptHasOpcodeTag;
	dng_opcode_list list(stage);
	list.Parse(host, stream, byteCount, streamOffset);
	bool prevOptionalWarp2 = false;
	for (uint32 i = 0; i < list.Count(); i++) {
		const dng_opcode &op = list.Entry(i);
		const uint32 id = op.OpcodeID();
		if (prevOptionalWarp2 && (id == dngOpcode_WarpRectilinear || id == dngOpcode_WarpFisheye)) {
			continue;
		}
		if (op.MinVersion() > dngVersion_Current && op.WasReadFromStream()) {
			if (!op.Optional()) {
				throw CallbackError{kUnsupportedOpcode};
			}
			if (applyProfileCorrections) {
				flags |= kOptOptionalSkipped;
			}
			continue;
		}
		if (dynamic_cast<const dng_opcode_Unknown *>(&op)) {
			if (!op.Optional()) {
				throw CallbackError{kUnsupportedOpcode};
			}
			if (applyProfileCorrections) {
				flags |= kOptOptionalSkipped;
			}
			continue;
		}
		if (!op.IsValidForNegative(negative)) {
			ThrowBadFormat("invalid opcode for negative");
		}
		if (op.IsNOP()) {
			continue;
		}
		prevOptionalWarp2 = (op.Optional() && id == dngOpcode_WarpRectilinear2);
		if (!applyProfileCorrections && IsLensProfileOpcode(op)) {
			continue;
		}

		if (const auto *rect = dynamic_cast<const dng_opcode_BaseWarpRectilinear *>(&op)) {
			flags |= StageWarpFlag(stage);
			const dng_warp_params_rectilinear &p = rect->Params();
			if (p.fPlanes <= 1) {
				flags |= kOptEmbeddedDistortion;
			} else {
				const uint32 refPlane = p.fPlanes >= 3 ? 1u : 0u;
				if (!p.IsNOP(refPlane)) {
					flags |= kOptEmbeddedDistortion;
				}
				if (RectilinearHasDistinctPlanes(p)) {
					flags |= kOptEmbeddedTca;
				}
			}
		} else if (const auto *fish = dynamic_cast<const dng_opcode_WarpFisheye *>(&op)) {
			flags |= StageWarpFlag(stage) | kOptEmbeddedDistortion;
			if (FisheyeHasDistinctPlanes(host, *fish)) {
				flags |= kOptEmbeddedTca;
			}
		} else if (dynamic_cast<const dng_opcode_FixVignetteRadial *>(&op)) {
			flags |= kOptEmbeddedVignetting;
		} else if (const auto *gm = dynamic_cast<const dng_opcode_GainMap *>(&op)) {
			if (GainMapHasEffect(gm->GainMap())) {
				flags |= kOptEmbeddedVignetting;
			}
		} else if (dynamic_cast<const dng_filter_opcode *>(&op)) {
			flags |= StageWarpFlag(stage);
		}
	}
}

void FillInfo(dng_host &host, dng_stream &stream, dng_info &info, dng_negative &negative,
			  bool applyProfileCorrections, FilmDngInfo *out) {
	const dng_ifd &raw = *info.fIFD[info.fMainIndex];
	out->encoded_width = raw.fImageWidth;
	out->encoded_height = raw.fImageLength;
	out->compression = raw.fCompression;
	out->bits_per_sample = raw.fBitsPerSample[0];
	out->sample_format = raw.fSampleFormat[0];
	out->is_cfa = raw.fPhotometricInterpretation == piCFA ? 1 : 0;
	out->color_planes = negative.ColorChannels();
	out->dng_version = info.fShared->fDNGVersion;

	dng_rect active(raw.fImageLength, raw.fImageWidth);
	if (const dng_linearization_info *lin = negative.GetLinearizationInfo()) {
		active = lin->fActiveArea;
	}
	out->active_x = active.l;
	out->active_y = active.t;
	out->active_w = active.W();
	out->active_h = active.H();
	// DefaultCropArea() is relative to ActiveArea (0, 0) in Stage 3 coordinates.
	const dng_rect crop = negative.DefaultCropArea();
	out->crop_x = crop.l;
	out->crop_y = crop.t;
	out->crop_w = crop.W();
	out->crop_h = crop.H();

	const dng_orientation orientation = negative.Orientation();
	out->orientation = orientation.GetTIFF();
	const uint32 fw = negative.DefaultFinalWidth();
	const uint32 fh = negative.DefaultFinalHeight();
	if (orientation.FlipD()) {
		out->width = fh;
		out->height = fw;
	} else {
		out->width = fw;
		out->height = fh;
	}

	uint32_t flags = 0;
	if (negative.DefaultScaleH().As_real64() != 1.0 ||
		negative.DefaultScaleV().As_real64() != 1.0 ||
		negative.BestQualityScale().As_real64() != 1.0) {
		flags |= kOptNonUnitScale;
	}
	if (negative.HasProfileGainTableMap()) {
		flags |= kOptHasProfileGainTable;
	}

	dng_camera_profile profile;
	const bool hasProfile = negative.GetProfileByID(dng_camera_profile_id(), profile);
	out->has_forward_matrix = hasProfile && profile.ForwardMatrix1().NotEmpty() ? 1 : 0;
	if (hasProfile) {
		if (profile.ToneCurve().IsValid() && !profile.ToneCurve().IsNull()) {
			flags |= kOptStrippedToneCurve;
		}
		if (profile.HasProfileGainTableMap()) {
			flags |= kOptHasProfileGainTable;
		}
	}

	InspectOpcodeList(host, stream, negative, 1, raw.fOpcodeList1Count, raw.fOpcodeList1Offset, applyProfileCorrections, flags);
	InspectOpcodeList(host, stream, negative, 2, raw.fOpcodeList2Count, raw.fOpcodeList2Offset, applyProfileCorrections, flags);
	InspectOpcodeList(host, stream, negative, 3, raw.fOpcodeList3Count, raw.fOpcodeList3Offset, applyProfileCorrections, flags);
	out->optical_flags = flags;
	out->baseline_exposure = negative.TotalBaselineExposure(dng_camera_profile_id());
}

// The film engine applies its own tone response; drop profile tone curves so
// dng_render keeps scene-linear values (identity curve set by the caller).
void StripProfileToneCurves(dng_negative &negative) {
	const uint32 count = negative.ProfileCount();
	if (count == 0) return;
	dng_tone_curve invalid;
	invalid.SetInvalid();
	std::vector<std::unique_ptr<dng_camera_profile>> copies;
	for (uint32 i = 0; i < count; i++) {
		copies.emplace_back(new dng_camera_profile(negative.ProfileByIndex(i)));
		copies.back()->SetToneCurve(invalid);
	}
	negative.ClearProfiles();
	for (auto &copy : copies) {
		AutoPtr<dng_camera_profile> owned(copy.release());
		negative.AddProfile(owned);
	}
}

void WriteAll(int fd, const uint8_t *data, size_t size, uint64_t offset) {
	while (size > 0) {
		const ssize_t n = pwrite(fd, data, size, (off_t) offset);
		if (n < 0) {
			if (errno == EINTR) continue;
			if (errno == ENOSPC || errno == EDQUOT) {
				Throw_dng_error(dng_error_write_file, nullptr, "ENOSPC");
			}
			Throw_dng_error(dng_error_write_file, nullptr, "pwrite");
		}
		if (n == 0) Throw_dng_error(dng_error_write_file, nullptr, "short write");
		data += n;
		size -= (size_t) n;
		offset += (uint64_t) n;
	}
}

int32_t Run(const char *path, int outFd, const char *scratchDir, uint64_t heapBudget,
			uint64_t diskBudget, const uint8_t *cancel, uint32_t threads,
			bool applyProfileCorrections, const FilmLensfunHooks *lensfun, FilmDngInfo *out) {
	std::memset(out, 0, sizeof(*out));
	std::call_once(gXmpInit, [] { dng_xmp_sdk::InitializeSDK(); });
	BudgetAllocator allocator(scratchDir ? scratchDir : ".", heapBudget, diskBudget);
	CancelSniffer sniffer(cancel);
	bool noSpaceWrite = false;
	try {
		FilmHost host(&allocator, &sniffer, threads);
		host.SetSaveDNGVersion(0);
		host.SetSaveLinearDNG(false);
		host.SetKeepOriginalFile(false);
		host.SetApplyProfileCorrections(applyProfileCorrections);
		dng_file_stream stream(path);
		dng_info info;
		info.Parse(host, stream);
		info.PostParse(host);
		if (!info.IsValidDNG()) {
			SetMessage(out, "not a valid DNG");
			return kInvalidInput;
		}
		AutoPtr<dng_negative> negative(host.Make_dng_negative());
		negative->Parse(host, stream, info);
		negative->PostParse(host, stream, info);
		FillInfo(host, stream, info, *negative, applyProfileCorrections, out);
		if (outFd < 0) {
			return kOk;  // metadata-only probe
		}

		host.ArmLensfun(applyProfileCorrections ? lensfun : nullptr);
		negative->ReadStage1Image(host, stream, info);
		negative->ClearRawImage();
		negative->BuildStage2Image(host);
		negative->ClearStage1Image();
		negative->BuildStage3Image(host);
		host.ArmLensfun(nullptr);
		negative->ClearTransparencyMask();
		StripProfileToneCurves(*negative);

		const dng_orientation orientation = negative->Orientation();
		MappedOutput outMap;
		const size_t outBytes = (size_t) out->width * out->height * 12;
		if (void *mapped = outMap.Map(outFd, outBytes)) {
			const dng_point finalSize((int32) negative->DefaultFinalHeight(),
									  (int32) negative->DefaultFinalWidth());
			const uint32 skip = (negative->DefaultCropArea().Size() != finalSize) ? 1u : 0u;
			host.ArmDirectOutput(mapped, out->width, out->height, orientation, skip);
		}

		dng_render render(host, *negative);
		render.SetFinalSpace(dng_space_sRGB_Linear::Get());
		render.SetFinalPixelType(ttFloat);
		render.SetToneCurve(dng_1d_identity::Get());
		render.SetShadows(0.0);
		AutoPtr<dng_image> image(render.Render());
		negative.Reset();  // release stage 3 before any fallback write

		if (host.DirectOutputUsed()) {
			image.Reset();
			outMap.Reset();
			out->heap_peak_bytes = allocator.heapPeak();
			out->disk_peak_bytes = allocator.diskPeak();
			return kOk;
		}
		outMap.Reset();

		image->Rotate(orientation);
		const dng_rect bounds = image->Bounds();
		if ((uint32_t) bounds.W() != out->width || (uint32_t) bounds.H() != out->height) {
			Throw_dng_error(dng_error_unknown, nullptr, "render size mismatch");
		}
		const uint32 w = bounds.W();
		const uint32 planes = image->Planes();
		const uint32 band = 64;
		std::vector<real32> planar((size_t) w * band * 3);
		std::vector<uint8_t> rgb((size_t) w * band * 12);
		for (int32 y = bounds.t; y < bounds.b; y += band) {
			dng_abort_sniffer::SniffForAbort(&sniffer);
			const uint32 rows = std::min<uint32>(band, bounds.b - y);
			dng_pixel_buffer buffer(dng_rect(y, bounds.l, y + rows, bounds.r), 0, 3, ttFloat,
									pcPlanar, planar.data());
			if (planes == 1) {
				buffer.fPlanes = 1;
				image->Get(buffer, dng_image::edge_none);
				std::memcpy(planar.data() + (size_t) w * rows, planar.data(), (size_t) w * rows * 4);
				std::memcpy(planar.data() + (size_t) w * rows * 2, planar.data(), (size_t) w * rows * 4);
			} else {
				image->Get(buffer, dng_image::edge_none);
			}
			const size_t planeStride = (size_t) buffer.fPlaneStep;
			const size_t rowStride = (size_t) buffer.fRowStep;
			uint8_t *dst = rgb.data();
			for (uint32 r = 0; r < rows; r++) {
				const real32 *p0 = planar.data() + r * rowStride;
				for (uint32 x = 0; x < w; x++) {
					const real32 px[3] = {p0[x], p0[x + planeStride], p0[x + 2 * planeStride]};
					std::memcpy(dst, px, 12);  // little-endian targets only
					dst += 12;
				}
			}
			try {
				WriteAll(outFd, rgb.data(), (size_t) w * rows * 12, (uint64_t) (y - bounds.t) * w * 12);
			} catch (const dng_exception &) {
				noSpaceWrite = errno == ENOSPC || errno == EDQUOT;
				throw;
			}
		}
		out->heap_peak_bytes = allocator.heapPeak();
		out->disk_peak_bytes = allocator.diskPeak();
		return kOk;
	} catch (const CallbackError &ce) {
		SetMessage(out, ce.status == kUnsupportedOpcode ? "unsupported_opcode" : "lensfun_callback");
		return ce.status;
	} catch (const dng_exception &e) {
		SetMessage(out, "dng_exception");
		if (noSpaceWrite) return kNoSpace;
		return MapDngError(e.ErrorCode(), &allocator);
	} catch (const std::bad_alloc &) {
		SetMessage(out, "bad_alloc");
		return kMemoryBudget;
	} catch (const std::exception &e) {
		SetMessage(out, e.what());
		return kInternal;
	} catch (...) {
		SetMessage(out, "unknown exception");
		return kInternal;
	}
}

}  // namespace

// Metadata-only parse. Never reads pixel data.
FILM_EXPORT int32_t film_dng_sdk_probe(const char *path, uint32_t apply_profile_corrections, FilmDngInfo *out) {
	if (!path || !out) return kInternal;
	return Run(path, -1, nullptr, 0, 0, nullptr, 1, apply_profile_corrections != 0, nullptr, out);
}

// Full decode into an already created, empty output file descriptor.
FILM_EXPORT int32_t film_dng_sdk_decode(const char *path, int out_fd, const char *scratch_dir,
										uint64_t heap_budget, uint64_t disk_budget,
										const uint8_t *cancel, uint32_t threads,
										uint32_t apply_profile_corrections,
										const FilmLensfunHooks *lensfun,
										FilmDngInfo *out) {
	if (!path || out_fd < 0 || !scratch_dir || !out) return kInternal;
	return Run(path, out_fd, scratch_dir, heap_budget, disk_budget, cancel, threads,
			   apply_profile_corrections != 0, lensfun, out);
}
