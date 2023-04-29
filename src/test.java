import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class test {
    public static void main(String[] args) {
        MyThreadPoolExecutor.CallerRunsPolicy callerRunsPolicy = new MyThreadPoolExecutor.CallerRunsPolicy();
        MyThreadPoolExecutor executor = new MyThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1),callerRunsPolicy);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                System.out.println("任务1执行");
            }
        });
        System.out.println("main方法执行");
    }
}
