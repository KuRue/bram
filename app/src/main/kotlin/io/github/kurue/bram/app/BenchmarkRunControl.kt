package io.github.kurue.bram.app

import kotlinx.coroutines.Job

/**
 * Owns the lifetime of the one benchmark the app can have in flight.
 *
 * Benchmarking stays automatic: nothing asks the user to start it and nothing has to be told to
 * finish it. That is why the run used to be fire-and-forget — [MainViewModel.runBenchmark] launched
 * into `viewModelScope` and dropped the [Job], which left no handle to stop it. A sustained run is
 * minutes of back-to-back decode with no rest, and on a phone that is a long time to be unable to
 * change your mind, so the run needs a way out that is not "leave the screen".
 *
 * Cancelling a job does not stop it writing. A coroutine cancelled between two suspension points
 * still finishes the rest of that segment, so a run cancelled mid-measurement would carry on and set
 * `benchmarkStatus` again — putting a spinner back on a card the user had just stopped. Each run
 * therefore takes a ticket, and a write counts only while that ticket is the current one. Cancelling
 * retires the ticket, which is what makes the settle final rather than merely likely.
 *
 * Deliberately free of Android types so it can be tested on the JVM.
 */
internal class BenchmarkRunControl {
    private val lock = Any()
    private var job: Job? = null
    private var generation = 0L

    /**
     * Begins a run and reserves its ticket. Counts as in flight from here, which is before the job
     * exists: [MainViewModel] sets the card's state before launching, so a cancel landing in the gap
     * between the two finds a run already marked rather than nothing at all.
     */
    fun begin(): Long = synchronized(lock) {
        generation += 1
        generation
    }

    /**
     * Binds [job] to [ticket]. A ticket retired before its job existed gets that job cancelled on
     * the spot, so a cancelled run can never come to life after the fact.
     *
     * Returns whether the job was accepted.
     */
    fun attach(ticket: Long, job: Job): Boolean = synchronized(lock) {
        if (generation == ticket) {
            this.job = job
            true
        } else {
            job.cancel()
            false
        }
    }

    /** Whether [ticket] still owns the run, and so may still write state. */
    fun isCurrent(ticket: Long): Boolean = synchronized(lock) { generation == ticket }

    /** Whether a run is in flight, which is what puts the card's Cancel within reach. */
    fun inFlight(): Boolean = synchronized(lock) { job?.isActive == true }

    /**
     * Stops the in-flight run and retires its ticket.
     *
     * Returns whether there was a run to stop, so the caller settles the card only when it actually
     * stopped something rather than claiming a cancellation that did not happen.
     */
    fun cancel(): Boolean = synchronized(lock) {
        generation += 1
        val running = job
        job = null
        // What the job *is*, not whether a handle is still held: a run that finished on its own
        // leaves its handle behind until its finally block reports in, and cancelling that would
        // claim to have stopped a run that had already stopped.
        val wasRunning = running?.isActive == true
        running?.cancel()
        wasRunning
    }

    /** The run finished of its own accord; drops its handle, but only if it is still the current one. */
    fun onFinished(ticket: Long) = synchronized(lock) {
        if (generation == ticket) job = null
    }

    companion object {
        /**
         * The status a cancelled run settles on.
         *
         * A terminal state rather than a cleared one: the card said a benchmark was running, so it
         * should say what became of it instead of going quiet as though it had never happened.
         */
        const val CANCELLED_STATUS = "Benchmark cancelled"
    }
}