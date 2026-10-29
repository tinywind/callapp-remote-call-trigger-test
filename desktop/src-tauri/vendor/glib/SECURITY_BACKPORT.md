# GLib security backport

This is the unmodified crates.io glib 0.18.5 source archive, except for the two-line fix in src/variant_iter.rs and this note. The crate archive SHA-256 is `233daaf6e83ae6a12a52055f568f9d7cf4671dabb78ff9560ab6da230ce00ee5`. Original license and copyright files are retained.

The fix backports [upstream gtk-rs-core PR 1343](https://github.com/gtk-rs/gtk-rs-core/pull/1343), commit `b5a4071e439bef2b5eea76c3aa25e5ae84839e34`, for [RUSTSEC-2024-0429](https://rustsec.org/advisories/RUSTSEC-2024-0429.html). The pointer passed to the variadic GLib out-parameter is mutable and passed through `&mut p`.

Tauri 2 uses GTK 3 and glib 0.18 on Linux; replacing only glib with 0.20 would violate the GTK dependency and native-library constraints. The Cargo patch uses this reviewed source without changing the upstream crate version. Version-only scanners can still report 0.18.5; this note documents the source fix rather than claiming an upstream 0.18 release exists.

The upstream optimized variant iterator tests reproduce SIGSEGV with the original crate on Rust 1.99.1. The same tests are required to pass against this patched copy before integration.
