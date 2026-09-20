package pl.ayeo.repo.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class Mailbox<M> {

  private static final int CAPACITY = 1000;

  private final BlockingQueue<Delivery<M>> waiting = new LinkedBlockingQueue<>(CAPACITY);

  private volatile Reply closed;

  record Delivery<M>(M message, CompletableFuture<Reply> answered) {

    boolean awaited() {
      return answered != null;
    }
  }

  void post(M message) {
    if (closed != null) return;
    put(new Delivery<>(message, null));
  }

  synchronized CompletableFuture<Reply> postAwaited(M message) {
    if (closed != null) return CompletableFuture.completedFuture(closed);
    Delivery<M> delivery = new Delivery<>(message, new CompletableFuture<>());
    if (!waiting.offer(delivery)) {
      delivery.answered().complete(Reply.error("the desk mailbox is full"));
    }
    return delivery.answered();
  }

  private void put(Delivery<M> delivery) {
    try {
      waiting.put(delivery);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while posting", e);
    }
  }

  synchronized void rejectWaiting(Reply reply) {
    closed = reply;
    Delivery<M> delivery;
    while ((delivery = waiting.poll()) != null) {
      if (delivery.awaited()) delivery.answered().complete(reply);
    }
  }

  Delivery<M> take(long millis) {
    try {
      return waiting.poll(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}
