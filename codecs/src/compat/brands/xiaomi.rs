use crate::{
    cfa_gainmap::{self, CfaGainMap},
    error::Result,
    tiff::Ifd,
};
/// Only bounded ASCII Make selects this adapter; individual model names are not used.
pub(crate) fn matches(root: &Ifd) -> bool {
    root.get(&271).is_some_and(|v| {
        v.typ == 2
            && v.count > 0
            && v.count <= 128
            && v.bytes.len() == v.count as usize
            && v.bytes.last() == Some(&0)
            && v.bytes[..v.bytes.len() - 1].eq_ignore_ascii_case(b"Xiaomi")
    })
}
/// Four disjoint CFA phases are a verified capability plan, not a claim that all
/// Xiaomi DNG variants share it. Required unsupported records still fail closed.
pub(crate) fn list2(bytes: &[u8], width: usize, height: usize) -> Result<(Vec<CfaGainMap>, bool)> {
    match cfa_gainmap::parse_four_phase(bytes, width, height) {
        Ok(maps) => Ok((maps, false)),
        Err(e) if e.code == "UNSUPPORTED_DNG_OPCODE" => {
            Ok((Vec::new(), crate::opcodes::optional_only(bytes)?))
        }
        Err(e) => Err(e),
    }
}
