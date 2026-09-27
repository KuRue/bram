#include "mem_bench.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace bram {
namespace {

// Keeps the sums observable so the compiler cannot drop the reads.
std::atomic<uint64_t> g_sink{0};

// Sums [begin, end) as 64-bit words. Four independent accumulators keep enough loads in flight to
// saturate DRAM rather than measure one dependency chain.
uint64_t sum_range(const uint64_t * begin, const uint64_t * end) {
#if defined(__aarch64__)
    uint64x2_t a0 = vdupq_n_u64(0), a1 = a0, a2 = a0, a3 = a0;
    const uint64_t * p = begin;
    for (; p + 8 <= end; p += 8) {
        a0 = vaddq_u64(a0, vld1q_u64(p));
        a1 = vaddq_u64(a1, vld1q_u64(p + 2));
        a2 = vaddq_u64(a2, vld1q_u64(p + 4));
        a3 = vaddq_u64(a3, vld1q_u64(p + 6));
    }
    uint64x2_t total = vaddq_u64(vaddq_u64(a0, a1), vaddq_u64(a2, a3));
    uint64_t sum = vgetq_lane_u64(total, 0) + vgetq_lane_u64(total, 1);
    for (; p < end; ++p) sum += *p;
    return sum;
#else
    uint64_t s0 = 0, s1 = 0, s2 = 0, s3 = 0;
    const uint64_t * p = begin;
    for (; p + 4 <= end; p += 4) {
        s0 += p[0];
        s1 += p[1];
        s2 += p[2];
        s3 += p[3];
    }
    for (; p < end; ++p) s0 += *p;
    return s0 + s1 + s2 + s3;
#endif
}

// One timed pass with [threads] readers over the whole buffer, in seconds. The clock starts once
// every thread is spinning at the gate, so thread creation is not billed as memory time.
double timed_pass(const uint64_t * words, size_t count, int threads) {
    std::atomic<int> ready{0};
    std::atomic<bool> go{false};
    std::vector<std::thread> workers;
    workers.reserve(static_cast<size_t>(threads));
    const size_t slice = count / static_cast<size_t>(threads);
    for (int t = 0; t < threads; ++t) {
        const uint64_t * begin = words + slice * static_cast<size_t>(t);
        const uint64_t * end = (t == threads - 1) ? words + count : begin + slice;
        workers.emplace_back([&ready, &go, begin, end] {
            ready.fetch_add(1, std::memory_order_acq_rel);
            while (!go.load(std::memory_order_acquire)) {
            }
            g_sink.fetch_add(sum_range(begin, end), std::memory_order_relaxed);
        });
    }
    while (ready.load(std::memory_order_acquire) < threads) {
    }
    const auto start = std::chrono::steady_clock::now();
    go.store(true, std::memory_order_release);
    for (auto & worker : workers) worker.join();
    const auto stop = std::chrono::steady_clock::now();
    return std::chrono::duration<double>(stop - start).count();
}

// 1, 2, 4, then every even count up to [max_threads], always ending on [max_threads] itself.
std::vector<int> thread_counts(int max_threads) {
    std::vector<int> counts;
    for (int n : {1, 2, 4}) {
        if (n <= max_threads) counts.push_back(n);
    }
    for (int n = 6; n < max_threads; n += 2) counts.push_back(n);
    if (counts.empty() || counts.back() != max_threads) counts.push_back(max_threads);
    return counts;
}

}  // namespace

std::string measure_read_bandwidth(size_t buffer_bytes, int max_threads, int passes) {
    max_threads = std::max(1, max_threads);
    passes = std::max(1, passes);
    buffer_bytes = std::max<size_t>(buffer_bytes, 16u << 20) & ~static_cast<size_t>(63);

    void * raw = std::aligned_alloc(64, buffer_bytes);
    if (raw == nullptr) throw std::runtime_error("could not allocate the bandwidth buffer");
    // Writing faults every page in, so the timed passes measure DRAM, not page faults, and a
    // non-zero pattern stops a zero page from being shared underneath the reads.
    std::memset(raw, 0x5a, buffer_bytes);
    const auto * words = static_cast<const uint64_t *>(raw);
    const size_t count = buffer_bytes / sizeof(uint64_t);

    std::ostringstream json;
    json << "{\"bufferBytes\":" << buffer_bytes << ",\"passes\":" << passes << ",\"results\":[";
    double peak = 0.0;
    int peak_threads = 1;
    bool first = true;
    for (int threads : thread_counts(max_threads)) {
        // Best of the passes: interference from the rest of the system only ever slows a pass.
        double best = 0.0;
        for (int pass = 0; pass < passes; ++pass) {
            const double seconds = timed_pass(words, count, threads);
            if (seconds > 0.0) best = std::max(best, static_cast<double>(buffer_bytes) / seconds / 1e9);
        }
        if (best > peak) {
            peak = best;
            peak_threads = threads;
        }
        if (!first) json << ",";
        first = false;
        json << "{\"threads\":" << threads << ",\"gbPerSec\":" << best << "}";
    }
    std::free(raw);
    json << "],\"peakGbPerSec\":" << peak << ",\"peakThreads\":" << peak_threads << "}";
    return json.str();
}

}  // namespace bram
