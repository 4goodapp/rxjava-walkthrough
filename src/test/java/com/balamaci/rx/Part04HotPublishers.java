package com.balamaci.rx;

import com.balamaci.rx.util.Helpers;
import io.reactivex.Observable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.ReplaySubject;
import io.reactivex.subjects.Subject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author sbalamaci
 */
public class Part04HotPublishers implements BaseTestObservables {

    /**
     * We've seen that with 'cold publishers', whenever a subscriber subscribes, each subscriber will get
     * it's version of emitted values independently
     *
     * Subjects keep reference to their subscribers and allow 'multicasting' to them.
     *
     *  for (Disposable<T> s : subscribers.get()) {
     *     s.onNext(t);
     *  }

     *
     * Subjects besides being traditional Observables you can use the same operators and subscribe to them,
     * are also an Observer, meaning you can invoke subject.onNext(value) from different parts in the code,
     * which means that you publish events which the Subject will pass on to their subscribers.
     *
     * Observable.create(subscriber -> {
     *      subscriber.onNext(val);
     * })
     * .map(...)
     * .subscribe(...);
     *
     * With Subjects you can call onNext from different parts of the code:
     * Subject<Integer> subject = ReplaySubject.create()
     *                              .map(...);
     *                              .subscribe(); //
     *
     * ...
     * subject.onNext(val);
     * ...
     * subject.onNext(val2);
     *
     * ReplaySubject keeps a buffer of events that it 'replays' to each new subscriber, first he receives a batch of missed
     * and only later events in real-time.
     *
     * PublishSubject - doesn't keep a buffer but instead, meaning if another subscriber subscribes later, it's going to loose events
     */

    private int counter = 0;

    @Test
    public void replaySubject() {
        Subject<Integer> subject = ReplaySubject.createWithSize(50);

//        Runnable pushAction = pushEventsToSubjectAction(subject, 10);
//        periodicEventEmitter(pushAction, 500, TimeUnit.MILLISECONDS);

        pushToSubject(subject, 0);
        pushToSubject(subject, 1);

        CountDownLatch latch = new CountDownLatch(2);
        log.info("Subscribing 1st");
        subject.subscribe(val -> log.info("Subscriber1 received {}", val), logError(), logComplete(latch));

        pushToSubject(subject, 2);

        log.info("Subscribing 2nd");
        subject.subscribe(val -> log.info("Subscriber2 received {}", val), logError(), logComplete(latch));
        pushToSubject(subject, 3);

        subject.onComplete();

        Helpers.wait(latch);
    }

    private void pushToSubject(Subject<Integer> subject, int val) {
        log.info("Pushing {}", val);
        subject.onNext(val);
    }

    @Test
    public void publishSubject() {
        Subject<Integer> subject = PublishSubject.create();

        Runnable action = () -> {
            if(counter == 10) {
                subject.onComplete();
                return;
            }

            counter ++;

            log.info("Emitted {}", counter);
            subject.onNext(counter);
        };
        periodicEventEmitter(action, 500, TimeUnit.MILLISECONDS);

        Helpers.sleepMillis(1000);
        log.info("Subscribing 1st");

        CountDownLatch latch = new CountDownLatch(2);
        subject
                .subscribe(val -> log.info("Subscriber1 received {}", val), logError(), logComplete(latch));

        Helpers.sleepMillis(1000);
        log.info("Subscribing 2nd");
        subject.subscribe(val -> log.info("Subscriber2 received {}", val), logError(), logComplete(latch));
        Helpers.wait(latch);
    }


    /**
     * Because reactive stream specs mandates that events should be ordered(cannot emit downstream two events simultaneously)
     * it means that it's illegal to call onNext,onComplete,onError from different threads.
     *
     * To make this easy there is the .toSerialized() operator that wraps the Subject inside a SerializedSubject
     * which basically just calls the onNext,.. methods of the wrapped Subject inside a synchronized block.
     */
    @Test
    public void callsToSubjectMethodsMustHappenOnSameThread() {
        Subject<Integer> subject = PublishSubject.create();

        CountDownLatch latch = new CountDownLatch(1);

        Subject<Integer> serializedSubject = subject.toSerialized();
        subject.subscribe(logNext(), logError(), logComplete(latch));

        new Thread(() -> serializedSubject.onNext(1), "thread1").start();
        new Thread(() -> serializedSubject.onNext(2), "thread2").start();
        new Thread(() -> serializedSubject.onComplete(), "thread3").start();

        Helpers.wait(latch);
    }

    /**
     * ConnectableObservable
     */
    @Test
    public void sharingResourcesBetweenObservablesWithConnectableObservable() {
        Observable<Integer> connectableObservable = Observable.<Integer>create(subscriber -> {
            log.info("Inside create()");
            ResourceConnectionHandler resourceConnectionHandler = new ResourceConnectionHandler() {
                @Override
                public void onMessage(Integer message) {
                    log.info("Emitting {}", message);
                    subscriber.onNext(message);
                }
            };
            resourceConnectionHandler.connect();

            subscriber.setCancellable(resourceConnectionHandler::disconnect);
        }).share();
//        }).publish().refCount(); // publish().refCount() equals share()

        CountDownLatch latch = new CountDownLatch(2);
        connectableObservable
                .take(5)
                .subscribe((val) -> log.info("Subscriber1 received: {}", val), logError(), logComplete(latch));

        Helpers.sleepMillis(1000);

        log.info("Subscribing 2nd");
        connectableObservable
                .take(2)
                .subscribe((val) -> log.info("Subscriber2 received: {}", val), logError(), logComplete(latch));
        Helpers.wait(latch);
    }

    @Test
    public void connectable() {
        ConnectableObservable<Integer> connectableObservable = Observable.<Integer>create(subscriber -> {
            log.info("Inside create()");
            ResourceConnectionHandler resourceConnectionHandler = new ResourceConnectionHandler() {
                @Override
                public void onMessage(Integer message) {
                    log.info("Emitting {}", message);
                    subscriber.onNext(message);
                }
            };
            resourceConnectionHandler.connect();

            subscriber.setCancellable(resourceConnectionHandler::disconnect);
        }).publish();

        log.info("Before subscribing");
        CountDownLatch latch = new CountDownLatch(1);
        connectableObservable
                .take(4)
                .subscribe((val) -> log.info("Subscriber1 received: {}", val), logError(), logComplete(latch));
        log.info("After subscribing");

        connectableObservable.connect();
        Helpers.wait(latch);
    }


    private ScheduledExecutorService periodicEventEmitter(Runnable action,
                                                          int period, TimeUnit timeUnit) {
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
        scheduledExecutorService.scheduleAtFixedRate(action, 0, period, timeUnit);

        return scheduledExecutorService;
    }

    private Runnable pushEventsToSubjectAction(Subject<Integer> subject, int maxEvents) {
        return () -> {
            if(counter == maxEvents) {
                subject.onComplete();
                return;
            }

            counter ++;

            log.info("Emitted {}", counter);
            subject.onNext(counter);
        };
    }

    private abstract class ResourceConnectionHandler {

        ScheduledExecutorService scheduledExecutorService;

        private int counter;

        public void connect() {
            log.info("**Opening connection");

            scheduledExecutorService = periodicEventEmitter(() -> {
                counter ++;
                onMessage(counter);
            }, 500, TimeUnit.MILLISECONDS);
        }

        public abstract void onMessage(Integer message);

        public void disconnect() {
            log.info("**Shutting down connection");
            scheduledExecutorService.shutdown();
        }
    }

}
