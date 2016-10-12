package org.briarproject.system;

import android.app.Application;

import org.briarproject.android.api.AndroidExecutor;
import org.briarproject.android.api.BackgroundExecutor;
import org.briarproject.api.lifecycle.LifecycleManager;
import org.briarproject.api.system.LocationUtils;
import org.briarproject.api.system.SeedProvider;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static java.util.concurrent.TimeUnit.SECONDS;

@Module
public class AndroidSystemModule {

	public static class EagerSingletons {
		@Inject
		@BackgroundExecutor
		ExecutorService executorService;
	}

	private final ExecutorService bgExecutor;

	public AndroidSystemModule() {
		// Use an unbounded queue
		BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
		// Discard tasks that are submitted during shutdown
		RejectedExecutionHandler policy =
				new ThreadPoolExecutor.DiscardPolicy();
		// Use a single thread and keep it in the pool for 60 secs
		bgExecutor = new ThreadPoolExecutor(0, 1, 60, SECONDS, queue,
				policy);
	}

	@Provides
	@Singleton
	@BackgroundExecutor
	ExecutorService provideDatabaseExecutorService(
			LifecycleManager lifecycleManager) {
		lifecycleManager.registerForShutdown(bgExecutor);
		return bgExecutor;
	}

	@Provides
	@Singleton
	@BackgroundExecutor
	Executor provideDatabaseExecutor(
			@BackgroundExecutor ExecutorService bgExecutor) {
		return bgExecutor;
	}

	@Provides
	@Singleton
	SeedProvider provideSeedProvider(Application app) {
		return new AndroidSeedProvider(app);
	}

	@Provides
	LocationUtils provideLocationUtils(Application app) {
		return new AndroidLocationUtils(app);
	}

	@Provides
	@Singleton
	AndroidExecutor provideAndroidExecutor(Application app) {
		return new AndroidExecutorImpl(app);
	}
}
