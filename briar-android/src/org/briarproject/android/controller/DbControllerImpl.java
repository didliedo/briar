package org.briarproject.android.controller;

import org.briarproject.android.api.BackgroundExecutor;
import org.briarproject.api.lifecycle.LifecycleManager;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

public class DbControllerImpl implements DbController {

	private static final Logger LOG =
			Logger.getLogger(DbControllerImpl.class.getName());

	private final Executor bgExecutor;
	private final LifecycleManager lifecycleManager;

	@Inject
	public DbControllerImpl(@BackgroundExecutor Executor bgExecutor,
			LifecycleManager lifecycleManager) {
		this.bgExecutor = bgExecutor;
		this.lifecycleManager = lifecycleManager;
	}

	@Override
	public void runOnDbThread(final Runnable task) {
		bgExecutor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					task.run();
				} catch (InterruptedException e) {
					LOG.warning("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				}
			}
		});
	}
}
