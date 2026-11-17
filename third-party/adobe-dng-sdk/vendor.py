#!/usr/bin/env python3
"""Re-create sdk/ from the official Adobe DNG SDK zip.

Usage: python3 vendor.py dng_sdk_1_7_1_2802_20261110.zip

Only the files needed to build the DNG reader are copied. Paths inside sdk/
mirror the upstream archive below dng_sdk_1_7_1/. The upstream zip checksum is
verified first, and SHA256SUMS is regenerated for the vendored subset so later
edits are detectable (see FILMROOM_CHANGES.md for intentional local changes).
"""
import hashlib
import pathlib
import shutil
import sys
import zipfile

UPSTREAM_SHA256 = "e9bb04e956da3605d8ef4f821bdb979a250c22546b0332b7f81a93b11da7cceb"
TOP = "dng_sdk_1_7_1/"
HERE = pathlib.Path(__file__).resolve().parent
DEST = HERE / "sdk"

TOP_FILES = [
    "LICENSE.txt", "DNG_ReadMe.txt", "JPEG_ReadMe.txt", "JXL_ReadMe.txt", "XMP_ReadMe.txt",
]
JPEG_SOURCES = (
    "jaricom jcapimin jcapistd jcarith jccoefct jccolor jcdctmgr jchuff jcinit jcmainct "
    "jcmarker jcmaster jcomapi jcparam jcprepct jcsample jctrans jdapimin jdapistd jdarith "
    "jdatadst jdatasrc jdcoefct jdcolor jddctmgr jdhuff jdinput jdmainct jdmarker jdmaster "
    "jdmerge jdpostct jdsample jdtrans jerror jfdctflt jfdctfst jfdctint jidctflt jidctfst "
    "jidctint jmemmgr jmemnobs jquant1 jquant2 jutils"
).split()
JPEG_OTHER = [
    "README", "change.log", "jconfig.txt", "jdct.h", "jerror.h", "jinclude.h", "jmemsys.h",
    "jmorecfg.h", "jpegint.h", "jpeglib.h", "jversion.h",
]
XT = "xmp/toolkit/"
XMP_FILES = [
    # XMPCore (reader/serializer only; no XMPFiles, no DOM model).
    *(f"XMPCore/source/{n}" for n in (
        "Android_Utils.cpp", "Android_Utils.hpp", "ExpatAdapter.cpp", "ParseRDF.cpp",
        "WXMPIterator.cpp", "WXMPMeta.cpp", "WXMPUtils.cpp", "XMPCore_Impl.cpp",
        "XMPCore_Impl.hpp", "XMPDocOps.hpp", "XMPIterator.cpp", "XMPIterator.hpp",
        "XMPMeta-GetSet.cpp", "XMPMeta-Parse.cpp", "XMPMeta-Serialize.cpp", "XMPMeta.cpp",
        "XMPMeta.hpp", "XMPMeta2.hpp", "XMPUtils-FileInfo.cpp", "XMPUtils.cpp",
        "XMPUtils.hpp")),
    "XMPCore/resource/android/expat_config.h",
    "XMPCore/resource/linux/expat_config.h",
    "XMPCore/third-party/expat/ReadMe.txt",
    *(f"XMPCore/third-party/expat/public/lib/{n}" for n in (
        "ascii.h", "asciitab.h", "expat.h", "expat_external.h", "iasciitab.h", "internal.h",
        "latin1tab.h", "nametab.h", "siphash.h", "utf8tab.h", "xmlparse.c", "xmlrole.c",
        "xmlrole.h", "xmltok.c", "xmltok.h", "xmltok_impl.c", "xmltok_impl.h",
        "xmltok_ns.c")),
    "build/XMP_BuildInfo.h",
    *(f"public/include/{n}" for n in (
        "TXMPDocOps.hpp", "TXMPIterator.hpp", "TXMPMeta.hpp", "TXMPUtils.hpp", "XMP.hpp",
        "XMP.incl_cpp", "XMP_Const.h", "XMP_Environment.h", "XMP_Version.h",
        "XMPCommon/XMPCommonDefines.h", "XMPCore/XMPCoreDefines.h",
        "client-glue/TXMPDocOps.incl_cpp", "client-glue/TXMPIterator.incl_cpp",
        "client-glue/TXMPMeta.incl_cpp", "client-glue/TXMPUtils.incl_cpp",
        "client-glue/WXMPDocOps.hpp", "client-glue/WXMPIterator.hpp",
        "client-glue/WXMPMeta.hpp", "client-glue/WXMPUtils.hpp",
        "client-glue/WXMP_Common.hpp")),
    *(f"source/{n}" for n in (
        "ExpatAdapter.hpp", "SafeStringAPIs.cpp", "SafeStringAPIs.h", "SafeTypes.h",
        "SuppressSAL.h", "UnicodeConversions.cpp", "UnicodeConversions.hpp",
        "UnicodeInlines.incl_cpp", "XMLParserAdapter.hpp", "XML_Node.cpp",
        "XMP_LibUtils.cpp", "XMP_LibUtils.hpp")),
    "third-party/zuid/interfaces/MD5.h",
    "third-party/zuid/sources/MD5.cpp",
]


def wanted(names):
    out = [TOP + n for n in TOP_FILES]
    out += sorted(n for n in names
                  if n.startswith(TOP + "dng_sdk/source/") and not n.endswith("/"))
    out += [f"{TOP}libjpeg/{s}.c" for s in JPEG_SOURCES]
    out += [f"{TOP}libjpeg/{s}" for s in JPEG_OTHER]
    out += [TOP + XT + s for s in XMP_FILES]
    return out


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    archive = pathlib.Path(sys.argv[1])
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != UPSTREAM_SHA256:
        sys.exit(f"unexpected upstream checksum {digest}")
    if DEST.exists():
        shutil.rmtree(DEST)
    sums = []
    with zipfile.ZipFile(archive) as z:
        names = set(z.namelist())
        for name in wanted(names):
            if name not in names:
                sys.exit(f"missing from upstream archive: {name}")
            rel = name[len(TOP):]
            data = z.read(name)
            target = DEST / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sums.append(f"{hashlib.sha256(data).hexdigest()}  sdk/{rel}")
    (HERE / "SHA256SUMS").write_text("\n".join(sums) + "\n")
    print(f"vendored {len(sums)} files into {DEST}")


if __name__ == "__main__":
    main()
