use yap_stream_attachments::*;

fn main() {
    println!("{:>9} {:>8} {:>10} {:>6}", "plain", "segments", "encrypted", "small?");
    for n in [0usize, 1, 65_535, 65_536, 65_537, 262_080, 262_081] {
        let e = encrypted_size(n);
        println!("{n:>9} {:>8} {e:>10} {:>6}", segment_count(n), e <= SMALL_MAX_ENCRYPTED);
    }
}
