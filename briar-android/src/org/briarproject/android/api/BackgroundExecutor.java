package org.briarproject.android.api;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation for injecting the executor for background tasks. Also used for
 * annotating methods that should run on the background executor.
 * <p>
 * This executor can be used to ensure that updates to the UI occur in an order
 * that's consistent with updates to the database, by running database queries
 * on this executor and posting any event-based updates to the UI from this
 * executor.
 * </p>
 * <p>
 * The contract of this executor is that tasks are executed in the order
 * they're submitted, tasks are not executed concurrently, and submitting a
 * task will never block.
 * </p>
 */
@Qualifier
@Target({FIELD, METHOD, PARAMETER})
@Retention(RUNTIME)
public @interface BackgroundExecutor {
}
