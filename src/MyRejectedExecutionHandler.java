//拒绝策略接口
public interface MyRejectedExecutionHandler {

    //参数1  触发拒绝策略的任务
    //参数2  自定义的线程池
    void rejectedExecution(Runnable r, MyThreadPool executor);
}