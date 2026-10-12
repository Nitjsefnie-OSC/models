use std::{slice, sync::Barrier, thread};

struct SharedBuffer(*mut u32);
// The experiment tests whether the ranges, not merely the writes, are disjoint.
unsafe impl Sync for SharedBuffer {}

impl SharedBuffer {
    unsafe fn view(&self, start: usize, length: usize) -> &mut [u32] {
        unsafe { slice::from_raw_parts_mut(self.0.add(start), length) }
    }
}

fn main() {
    let disjoint = std::env::args().nth(1).as_deref() == Some("disjoint");
    let mut values = vec![0_u32; 64];
    let shared = &SharedBuffer(values.as_mut_ptr());
    let barrier = &Barrier::new(2);
    thread::scope(|scope| {
        for worker in 0..2 {
            scope.spawn(move || {
                let (start, length, index) = if disjoint {
                    (worker * 32, 32, 0)
                } else {
                    (0, 64, worker * 32)
                };
                let output = unsafe { shared.view(start, length) };
                barrier.wait();
                output[index] = worker as u32 + 1;
            });
        }
    });
    assert_eq!(values[0], 1);
    assert_eq!(values[32], 2);
    println!("PASS disjoint={disjoint}");
}
