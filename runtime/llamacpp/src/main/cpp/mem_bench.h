#pragma once

#include <string>

namespace bram {

// Measures sustained DRAM read bandwidth: the ceiling on decode speed, since generating one token
// of a dense model reads every weight once. Returns a JSON object:
//   {"bufferBytes":N,"passes":P,"results":[{"threads":t,"gbPerSec":x},...],
//    "peakGbPerSec":x,"peakThreads":t}
// GB is 1e9 bytes. Throws std::runtime_error when the buffer cannot be allocated.
std::string measure_read_bandwidth(size_t buffer_bytes, int max_threads, int passes);

}  // namespace bram
