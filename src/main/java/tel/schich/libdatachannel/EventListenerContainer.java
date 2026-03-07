package tel.schich.libdatachannel;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EventListenerContainer<T> implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(EventListenerContainer.class.getName());

    private final String eventName;
    private final Consumer<Boolean> lifecycleCallback;
    private final List<T> listeners;
    private final Lock changeLock;
    private volatile boolean closed;

    public EventListenerContainer(String eventName, Consumer<Boolean> lifecycleCallback) {
        this.eventName = eventName;
        this.lifecycleCallback = lifecycleCallback;
        this.listeners = new CopyOnWriteArrayList<>();
        this.changeLock = new ReentrantLock();
        this.closed = false;
    }

    public String eventName() {
        return eventName;
    }

    void invoke(Consumer<T> invoker) {
        if (closed) {
            LOGGER.log(Level.WARNING, "Invoke attempted on closed container for event {0}", eventName);
            return;
        }
        for (T listener : this.listeners) {
            try {
                invoker.accept(listener);
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "Handler for event " + eventName + " failed!", t);
            }
        }
    }

    public void register(T listener) {
        boolean wasEmpty;
        changeLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Container for event " + eventName + " is already closed!");
            }
            wasEmpty = listeners.isEmpty();
            listeners.add(listener);
        } finally {
            changeLock.unlock();
        }
        if (wasEmpty) {
            lifecycleCallback.accept(true);
        }
    }

    public boolean deregister(T listener) {
        boolean isNowEmpty;
        changeLock.lock();
        try {
            if (!listeners.remove(listener)) {
                return false;
            }
            isNowEmpty = listeners.isEmpty();
        } finally {
            changeLock.unlock();
        }
        if (isNowEmpty) {
            lifecycleCallback.accept(false);
        }
        return true;
    }

    private boolean internalDeregisterAll() {
        changeLock.lock();
        try {
            if (closed) {
                return false;
            }
            boolean triggerLifecycleCallback = !listeners.isEmpty();
            listeners.clear();
            return triggerLifecycleCallback;
        } finally {
            changeLock.unlock();
        }
    }

    public void deregisterAll() {
        if (internalDeregisterAll()) {
            lifecycleCallback.accept(false);
        }
    }

    @Override
    public void close() {
        boolean triggerLifecycleCallback;
        changeLock.lock();
        try {
            if (closed) {
                return;
            }
            triggerLifecycleCallback = internalDeregisterAll();
            closed = true;
        } finally {
            changeLock.unlock();
        }
        if (triggerLifecycleCallback) {
            lifecycleCallback.accept(false);
        }
    }

    @Override
    public String toString() {
        return eventName;
    }
}
