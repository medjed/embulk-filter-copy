package org.embulk.filter.copy.executor;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.embulk.config.ConfigSource;
import org.embulk.exec.ExecutionResult;
import org.embulk.filter.copy.util.ElapsedTime;

import javax.annotation.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class LocalThreadExecutor
    extends EmbulkExecutor
{
    private static final String THREAD_NAME = "embulk executor service";
    private static final int NUM_THREADS = 1;
    private final ListeningExecutorService es;
    private ListenableFuture<ExecutionResult> future;

    LocalThreadExecutor(ExecutorTask task)
    {
        super(task);
        this.es = MoreExecutors.listeningDecorator(
                Executors.newFixedThreadPool(
                        NUM_THREADS,
                        r -> new Thread(r, THREAD_NAME)
                ));
    }

    @Override
    public void setup()
    {
    }

    @Override
    public void executeAsync(ConfigSource config)
    {
        logger.debug("execute with this config: {}", config);
        if (future != null) {
            throw new IllegalStateException("executeAsync is already called.");
        }
        future = es.submit(embulkRun(config));
        Futures.addCallback(future, resultFutureCallback());
    }

    @Override
    public void waitUntilExecutionFinished()
    {
        if (future == null) {
            throw new NullPointerException();
        }
        ElapsedTime.debugUntil(() -> future.isDone() || future.isCancelled(),
                logger, "embulk executor", 3000L);
    }

    @Override
    public void shutdown()
    {
        ElapsedTime.info(
                logger,
                "embulk executor service shutdown",
                es::shutdown);
    }

    private Callable<ExecutionResult> embulkRun(ConfigSource config)
    {
        return () -> newEmbulkEmbed().run(config);
    }

    private FutureCallback<ExecutionResult> resultFutureCallback()
    {
        return new FutureCallback<ExecutionResult>()
        {
            @Override
            public void onSuccess(@Nullable ExecutionResult result)
            {
                for (Throwable throwable : result.getIgnoredExceptions()) {
                    logger.warn("Ignored error ", throwable);
                }
                logger.info("Config diff: {}", result.getConfigDiff());
                logger.debug("ExecutionResult: {}", result);
            }

            @Override
            public void onFailure(Throwable t)
            {
                throw new RuntimeException(t);
            }
        };
    }
}
