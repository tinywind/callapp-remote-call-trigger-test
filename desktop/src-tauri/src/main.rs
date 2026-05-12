#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    remote_call_trigger_desktop_lib::run();
}
