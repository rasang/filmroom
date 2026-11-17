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
#include "dng_camera_profile.h"
#include "dng_color_space.h"
#include "dng_exceptions.h"
#include "dng_file_stream.h"
#include "dng_host.h"
#include "dng_ifd.h"
#include "dng_image.h"
#include "dng_info.h"
#include "dng_linearization_info.h"
#include "dng_memory.h"
#include "dng_negative.h"
#include "dng_orientation.h"
#include "dng_pixel_buffer.h"
#include "dng_render.h"
#include "dng_simple_image.h"
#include "dng_tag_values.h"
#include "dng_xmp_sdk.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
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

// dng_host with a small fixed worker pool for area tasks (the SDK default is
// single-threaded), plus optional zero-copy rendering directly into the
// pre-allocated output file mapping.
class FilmHost final : public dng_host {
public:
	FilmHost(dng_memory_allocator *allocator, dng_abort_sniffer *sniffer, uint32 threads)
		: dng_host(allocator, sniffer), fThreads(std::max<uint32>(1, threads)) {}

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
	void *fDirectMap = nullptr;
	uint32 fDirectW = 0;
	uint32 fDirectH = 0;
	dng_orientation fDirectOrientation;
	uint32 fDirectSkip = 0;
	bool fDirectUsed = false;
};

}  // namespace

extern "C" {

// Field order and types must match `DngSdkInfo` in src/dng_sdk.rs.
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
	double baseline_exposure;  // total EV applied by rendering
	uint64_t heap_peak_bytes;
	uint64_t disk_peak_bytes;
	char message[160];
};

}  // extern "C"

namespace {

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

void FillInfo(dng_host &host, dng_info &info, dng_negative &negative, FilmDngInfo *out) {
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

	dng_camera_profile profile;
	out->has_forward_matrix =
		negative.GetProfileByID(dng_camera_profile_id(), profile) &&
		profile.ForwardMatrix1().NotEmpty() ? 1 : 0;
	out->baseline_exposure = negative.TotalBaselineExposure(dng_camera_profile_id());
	(void) host;
}

// The film engine applies its own tone response; drop profile tone curves so
// dng_render keeps scene-linear values (identity curve set by the caller).
void StripProfileToneCurves(dng_negative &negative) {
	const uint32 count = negative.ProfileCount();
	if (count == 0) return;
	std::vector<std::unique_ptr<dng_camera_profile>> copies;
	for (uint32 i = 0; i < count; i++) {
		copies.emplace_back(new dng_camera_profile(negative.ProfileByIndex(i)));
		copies.back()->SetToneCurve(dng_tone_curve());
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
			uint64_t diskBudget, const uint8_t *cancel, uint32_t threads, FilmDngInfo *out) {
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
		FillInfo(host, info, *negative, out);
		if (outFd < 0) {
			return kOk;  // metadata-only probe
		}

		negative->ReadStage1Image(host, stream, info);
		negative->ClearRawImage();
		negative->BuildStage2Image(host);
		negative->ClearStage1Image();
		negative->BuildStage3Image(host);
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
			} catch (const dng_exception &e) {
				noSpaceWrite = errno == ENOSPC || errno == EDQUOT;
				throw;
			}
		}
		out->heap_peak_bytes = allocator.heapPeak();
		out->disk_peak_bytes = allocator.diskPeak();
		return kOk;
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
FILM_EXPORT int32_t film_dng_sdk_probe(const char *path, FilmDngInfo *out) {
	if (!path || !out) return kInternal;
	return Run(path, -1, nullptr, 0, 0, nullptr, 1, out);
}

// Full decode into an already created, empty output file descriptor.
FILM_EXPORT int32_t film_dng_sdk_decode(const char *path, int out_fd, const char *scratch_dir,
										uint64_t heap_budget, uint64_t disk_budget,
										const uint8_t *cancel, uint32_t threads,
										FilmDngInfo *out) {
	if (!path || out_fd < 0 || !scratch_dir || !out) return kInternal;
	return Run(path, out_fd, scratch_dir, heap_budget, disk_budget, cancel, threads, out);
}
