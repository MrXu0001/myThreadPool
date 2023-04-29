import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MyThreadPoolExecutor {
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY = (1 << COUNT_BITS) - 1;
    private static final int RUNNING = -1 << COUNT_BITS;
    private static final int SHUTDOWN = 0 << COUNT_BITS;
    private static final int STOP = 1 << COUNT_BITS;
    private static final int TIDYING = 2 << COUNT_BITS;
    private static final int TERMINATED = 3 << COUNT_BITS;
    private final ReentrantLock mainLock = new ReentrantLock();
    private static int runStateOf(int c) {
        return c & ~CAPACITY;
    }
    private static int workerCountOf(int c) {
        return c & CAPACITY;
    }
    private static int ctlOf(int rs, int wc) {
        return rs | wc;
    }
    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }
    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }
    private volatile boolean allowCoreThreadTimeOut;
    private long completedTaskCount;
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }
    protected void beforeExecute(Thread t, Runnable r) { }
    protected void afterExecute(Runnable r, Throwable t) { }

    //===================================以下为四个构造函数及其构造时需要传入的成员变量=====================================//
    private volatile int corePoolSize;
    private volatile int maximumPoolSize;
    private volatile long keepAliveTime;
    private final BlockingQueue<Runnable> workQueue;
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }
    private volatile ThreadFactory threadFactory;
    private final HashSet<Worker> workers = new HashSet<Worker>();
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }
    private volatile MyRejectedExecutionHandler handler;
    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }
    public MyThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              MyRejectedExecutionHandler handler) {
        if (corePoolSize < 0 ||
                maximumPoolSize <= 0 ||
                maximumPoolSize < corePoolSize ||
                keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }
    private static final MyRejectedExecutionHandler defaultHandler =
            new MyThreadPoolExecutor.AbortPolicy();

    public MyThreadPoolExecutor(int corePoolSize,
                                int maximumPoolSize,
                                long keepAliveTime,
                                TimeUnit unit,
                                BlockingQueue<Runnable> workQueue,
                                ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                threadFactory, defaultHandler);
    }

    public MyThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                Executors.defaultThreadFactory(), defaultHandler);
    }

    public MyThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              MyRejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
                Executors.defaultThreadFactory(), handler);
    }
    //==========================================以上为四个构造方法==========================================//

    //==========================================以下为四个拒绝策略，静态内部类==========================================//
    public static class CallerRunsPolicy implements MyRejectedExecutionHandler {
        public CallerRunsPolicy() { }
        public void rejectedExecution(Runnable r, MyThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    public static class AbortPolicy implements MyRejectedExecutionHandler {
        public AbortPolicy() { }
        public void rejectedExecution(Runnable r, MyThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                    " rejected from " +
                    e.toString());
        }
    }

    public static class DiscardPolicy implements MyRejectedExecutionHandler {
        public DiscardPolicy() { }
        public void rejectedExecution(Runnable r, MyThreadPoolExecutor e) {
        }
    }

    public static class DiscardOldestPolicy implements MyRejectedExecutionHandler {
        public DiscardOldestPolicy() { }
        public void rejectedExecution(Runnable r, MyThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();  // poll()方法会移除并返回队列头部的元素，失败则返回null，区别于take()方法会阻塞直到有元素可用
                e.execute(r);
            }
        }
    }
    //===========================================以上为四个拒绝策略=================================================//

    //===========================================以下为worker内部类================================================//
    private final class Worker extends AbstractQueuedSynchronizer implements Runnable {
        private static final long serialVersionUID = 6138294804551838833L;
        final Thread thread;
        Runnable firstTask;
        volatile long completedTasks;
        Worker(Runnable runnable) {
            setState(-1); // inhibit interrupts until runWorker
            firstTask = runnable;
            thread = getThreadFactory().newThread(this);
        }
        public void run() {
            runWorker(this);
        }

        protected boolean isHeldExclusively() {
            return getState() != 0;
        }  // 当前线程是否拥有排他锁

        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }
        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }
    //===========================================以上为worker内部类================================================//

    //===========================================以下为线程池的主要方法getTask======================================//
    // getTask()方法会在runWorker()方法中被调用，用于获取下一个任务，如果线程池关闭或者线程池中没有任务了，则返回null
    private Runnable getTask() {
        boolean timeout = false; // 非核心线程是否poll超时，核心线程没有超时一说
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) { // 如果线程池状态大于等于STOP，或者队列为空，则减少工作线程数并返回null
                decrementWorkerCount();
                return null;
            }
            int wc = workerCountOf(c);
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize; // 是否允许核心线程超时（线程数是否大于核心线程数）
            if ((wc > maximumPoolSize || (timed && timeout)) // 如果工作线程数大于最大线程数，或者线程数大于核心线程数且上次poll()超时，则减少工作线程数并返回null
                    && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))  // 减少工作线程数，因为任务队列中没任务需要处理了，不需要那么多的工作线程
                    return null;
                continue;
            }
            try {
                Runnable r = timed ?
                        workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) : // 线程数超出核心线程数则使用poll()方法（非阻塞），否则使用take()方法（阻塞）
                        workQueue.take();
                if (r != null)
                    return r;
                timeout = true;  // 只有线程数>核心线程数（即理解为非核心线程）且poll()方法超时才会执行到这里，否则（核心线程）一直阻塞在take()方法
            } catch (InterruptedException retry) {
                timeout = false;
            }
        }
    }
    //===========================================以上为线程池的主要方法getTask======================================//

    //===========================================以下为线程池的主要方法runWorker======================================//
    final void runWorker(MyThreadPoolExecutor.Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        w.unlock(); // allow interrupts
        boolean completedAbruptly = true;
        try {
            while (task != null || (task = getTask()) != null) {
                w.lock();
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted.  This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                if ((runStateAtLeast(ctl.get(), STOP) ||
                        (Thread.interrupted() &&
                                runStateAtLeast(ctl.get(), STOP))) &&
                        !wt.isInterrupted())
                    wt.interrupt();
                try {
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        afterExecute(task, thrown);
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            // getTask方法返回null，任务执行完毕，执行processWorkerExit()方法
            processWorkerExit(w, completedAbruptly);
        }
    }
    //===========================================以上为线程池的主要方法runWorker======================================//


    // processWorkerExit()方法会在runWorker()方法中被调用，用于处理工作线程退出的情况
    private void processWorkerExit(MyThreadPoolExecutor.Worker w, boolean completedAbruptly) {
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completedTaskCount += w.completedTasks;
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        tryTerminate();  // 关闭成功就不往下执行了

        int c = ctl.get();
        if (runStateLessThan(c, STOP)) { // 说明取消关闭线程池了，线程池处于shutdown或者running状态
            if (!completedAbruptly) { // 如果是正常执行完毕的，那么就不需要再添加新的工作线程了
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            addWorker(null, false);
        }
    }
    private static final boolean ONLY_ONE = true;
    private final Condition termination = mainLock.newCondition();
    // tryTerminate()方法会在processWorkerExit()方法中被调用，用于尝试终止线程池
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            //什么时候会直接返回,即取消关闭线程池？
            //1，线程池正处于RUNNING状态
            //or
            //2，线程池已经是TIDYING 或者 TERMINATE 状态就直接返回 因为如果已经是TIDYING 或者 TERMINATE状态下 说明其他线程正在尝试关闭线程池或者已经关闭了线程池
            //or
            //3，线程池处于SHUTDOWN状态，并且工作队列里面还有剩余任务 那么肯定不能将线程池转化为Terminated状态
            if (isRunning(c) ||
                    runStateAtLeast(c, TIDYING) ||
                    (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            // 如果是stop状态或者shutdown并且工作队列为空状态才会执行到这里
            if (workerCountOf(c) != 0) { // Eligible to terminate
                // 中断空闲线程
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        // 释放所有等待线程
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }
    protected void terminated() { }
    private int largestPoolSize;
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            //什么时候会出现添加工作线程失败的情况？
            //1，线程池的状态是 STOP 因为STOP状态不需要处理任何任务  那么不需要创建工作线程
            // OR
            //2，线程池的状态是 SHUTDOWN 并且 firstTask不为空  因为SHUTDOWN状态下 线程池只会处理工作队列里面的剩余的任务  那么不需要创建工作线程
            // OR
            //3，线程池的状态是 SHUTDOWN 并且 firstTask == null 并且 工作队列里面没有剩余的任务 那么不需要创建工作线程
            if (rs >= SHUTDOWN &&
                    ! (rs == SHUTDOWN &&
                            firstTask == null &&
                            ! workQueue.isEmpty()))
                return false;

            for (;;) {
                int wc = workerCountOf(c);
                if (wc >= CAPACITY ||
                        wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                c = ctl.get();  // Re-read ctl
                if (runStateOf(c) != rs)
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }
        // 进行到这里说明ctl的workerCount已经加1成功了，下面进行真正的添加工作线程的操作
        boolean workerStarted = false;
        boolean workerAdded = false;
        MyThreadPoolExecutor.Worker w = null;
        try {
            w = new MyThreadPoolExecutor.Worker(firstTask);
            final Thread t = w.thread;
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    int rs = runStateOf(ctl.get());
                    //什么时候会真正添加工作线程？
                    // 1, 线程池是RUNNING状态
                    // or
                    // 2, 线程池处于SHUTDOWN状态 但是 firstTask 为 null
                    // （此时需要添加线程去处理工作队列里面的任务  因为 SHUTDOWN状态下还会处理工作队列里面没有处理的任务）
                    if (rs < SHUTDOWN ||
                            (rs == SHUTDOWN && firstTask == null)) {
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        workers.add(w);
                        int s = workers.size();
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) {
                    //worker添加成功
                    //开启线程 实际上是调用 worker对象里面的 run方法 --> runWorker方法
                    t.start();  // 如果在这里出异常没执行到下面那行，所以下面finally中要回滚
                    workerStarted = true;
                }
            }
        } finally {
            if (! workerStarted)  // 如果添加工作线程失败了 那么需要进行回滚操作
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    private void addWorkerFailed(MyThreadPoolExecutor.Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w);
            decrementWorkerCount();
            //尝试关闭线程池 因为减了一个线程，去tryTerminate里面判断一下【工作线程为0】  or 【工作队列里面没有任务数】这两个成不成立，成立则会触发线程池关闭
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (MyThreadPoolExecutor.Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    //===========================================以下为线程池的核心方法executor======================================//
    public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        int c = ctl.get();
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command, true))
                return;
            //走到这里说明添加核心工作线程失败 需要重新获得ctl的值
            c = ctl.get();
        }
        if (isRunning(c) && workQueue.offer(command)) {
            int recheck = ctl.get();
            //什么时候执行拒绝策略？
            //【线程池不是RUNNING状态】 并且 【成功将当前任务从工作队列里面移除】
            //为什么会这么做 ？ 因为如果此时线程池不是RUNNING状态了 说明其他线程并发修改了线程池的状态
            //处于SHUTDOWN  或者 STOP 状态的都不会执行最新的任务 所以执行拒绝策略
            if (!isRunning(recheck) && remove(command))
                reject(command);
            //走到这里 【说明当前线程池的状态是 RUNNING 状态】  并且 【线程池里面没有工作线程 所以需要创建非核心线程去执行工作队列里面的任务】
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }
        // 走到这里说明工作队列已经满了,即上面的第二个条件没满足 需要创建非核心线程去执行工作队列里面的任务，
        else if (!addWorker(command, false))
            //假如添加非核心线程失败 什么会导致失败？ 即工作线程数已经超过了线程池的最大线程数量[maximumPoolSize]
            //执行拒绝策略
            reject(command);
    }
    //===========================================以上为线程池的核心方法============================================//


}
