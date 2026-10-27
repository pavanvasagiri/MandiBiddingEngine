package com.mandi.engine;

import com.mandi.model.Ask;
import com.mandi.model.Bid;

import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-crop order book. The two heaps implement the double auction:
 * {@code bids} is a Max-Heap (highest bid first, earliest timestamp on ties)
 * and {@code asks} is a Min-Heap (lowest reserve first, earliest timestamp on
 * ties) purely through the models' natural ordering.
 *
 * <p>{@link PriorityQueue} is not thread-safe, so every mutation and every
 * clearing pass must hold {@link #lock}. The enclosing
 * {@link DoubleAuctionEngine} stores books in a
 * {@link java.util.concurrent.ConcurrentHashMap} keyed by crop id.
 */
final class MarketBook {

    final PriorityQueue<Bid> bids = new PriorityQueue<>();
    final PriorityQueue<Ask> asks = new PriorityQueue<>();
    final ReentrantLock lock = new ReentrantLock();
}
