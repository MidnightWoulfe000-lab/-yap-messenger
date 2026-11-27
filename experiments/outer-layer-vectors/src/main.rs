//! Prints the outer layer test vectors. `cargo run > vectors.txt` refreshes
//! the committed copy.
fn main() {
    print!("{}", yap_outer_layer_vectors::render());
}
