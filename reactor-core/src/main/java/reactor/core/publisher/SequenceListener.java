/*
 * Copyright (c) 2022 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.function.Consumer;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import reactor.util.context.Context;

/**
 * A sequence listener which combines various handlers to be triggered per the corresponding {@link Flux} or {@link Mono} events.
 * This is similar to the "side effect" operators in {@link Flux} and {@link Mono}, but in a single listener class.
 * One such listener is created for each {@link Subscriber}-to-{@link Publisher} {@link Subscription}.
 * <p>
 * Both publisher-to-subscriber events and subscription events are handled. Some methods may seem close to {@link Subscriber}
 * and {@link Subscription} methods, and this is on purpose. The interfaces themselves are not directly used/implemented in
 * order to avoid misconstruing this for an actual Reactive Streams implementation and to avoid changing the signals by mistake.
 *
 * @author Simon Baslé
 */
interface SequenceListener<T> {

	/**
	 * Handle the very beginning of the {@link Subscriber}-{@link Publisher} interaction.
	 * This handler is invoked right before subscribing to the parent {@link Publisher}, as a downstream
	 * {@link Subscriber} has called {@link Publisher#subscribe(Subscriber)}.
	 * <p>
	 * Once the {@link Publisher} has acknowledged with a {@link Subscription}, the {@link #doOnSubscription()}
	 * handler will be invoked before that {@link Subscription} is passed down.
	 *
	 * @see #doOnSubscription()
	 * @see Flux#doFirst(Runnable)
	 */
	void doFirst() throws Throwable;

	/**
	 * Handle terminal signals after the signals have been propagated, as the final step.
	 * Only {@link SignalType#ON_COMPLETE}, {@link SignalType#ON_ERROR} or {@link SignalType#CANCEL} can be passed.
	 * This handler is invoked AFTER the terminal signal has been propagated, and if relevant AFTER the {@link #doAfterComplete()}
	 * or {@link #doAfterError(Throwable)} events. If propagation fails or if the doAfterXxx events throw, this handler is still
	 * invoked.
	 *
	 * @see Flux#doFinally(Consumer)
	 */
	void doFinally(SignalType terminationType) throws Throwable;

	/**
	 * Handle the fact that the upstream {@link Publisher} acknowledged {@link Subscription}.
	 * The {@link Subscription} is intentionally not exposed in order to avoid manipulation by the observer.
	 * <p>
	 * While {@link #doFirst} is invoked right as the downstream {@link Subscriber} is registered,
	 * this method is invoked as the upstream answers back with a {@link Subscription}.
	 *
	 * @see #doFirst()
	 * @see Flux#doOnSubscribe(Consumer)
	 */
	void doOnSubscription() throws Throwable;

	/**
	 * Handle a new request made by the downstream, exposing the demand.
	 *
	 * @param requested the downstream demand
	 */
	void doOnRequest(long requested) throws Throwable;

	/**
	 * Handle the downstream cancelling its currently observed {@link Subscription}.
	 * <p>
	 * This handler is invoked before propagating the cancellation upstream, while {@link #doFinally(SignalType)} is invoked after.
	 *
	 * @see #doFinally(SignalType)
	 */
	void doOnCancel() throws Throwable;

	/**
	 * Handle a new value emission from the source. This handler is invoked before the value is propagated downstream.
	 * <p>
	 * This handler is invoked before propagating the value downstream.
	 *
	 * @param value the emitted value
	 */
	void doOnNext(T value) throws Throwable;

	/**
	 * Handle graceful onComplete sequence termination.
	 * <p>
	 * This handler is invoked before propagating the completion downstream, while both
	 * {@link #doAfterComplete()} and {@link #doFinally(SignalType)} are invoked after.
	 *
	 * @see #doAfterComplete()
	 * @see #doFinally(SignalType)
	 */
	void doOnComplete() throws Throwable;

	/**
	 * Handle onError sequence termination.
	 * <p>
	 * This handler is invoked before propagating the error downstream, while both
	 * {@link #doAfterError(Throwable)} and {@link #doFinally(SignalType)} are invoked after.
	 *
	 * @param error the exception that terminated the sequence
	 * @see #doAfterError(Throwable)
	 * @see #doFinally(SignalType)
	 */
	void doOnError(Throwable error) throws Throwable;

	/**
	 * Handle graceful onComplete sequence termination, after onComplete has been propagated downstream.
	 * <p>
	 * This handler is invoked after propagating the completion downstream, similar to {@link #doFinally(SignalType)}
	 * and unlike {@link #doOnComplete()}.
	 * <p>
	 * In case of propagation error (calling {@link Subscriber#onComplete()} throws), this handler is NOT invoked.
	 * In contrast, if either the underlying onComplete propagation or the invocation of this handler fails,
	 * {@link #doFinally(SignalType)} is still invoked.
	 */
	void doAfterComplete() throws Throwable;

	/**
	 * Handle onError sequence termination after onError has been propagated downstream.
	 * <p>
	 * This handler is invoked after propagating the error downstream, similar to {@link #doFinally(SignalType)}
	 * and unlike {@link #doOnError(Throwable)}.
	 * <p>
	 * In case of propagation error (calling {@link Subscriber#onError(Throwable)} throws), this handler is NOT invoked.
	 * In contrast, if either the underlying onError propagation or the invocation of this handler fails,
	 * {@link #doFinally(SignalType)} is still invoked.
	 *
	 * @param error the exception that terminated the sequence
	 */
	void doAfterError(Throwable error) throws Throwable;

	/**
	 * Handle malformed {@link Subscriber#onNext(Object)}, which are onNext happening after the sequence has already terminated
	 * via {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)}.
	 * Note that after this handler is invoked, the value is automatically {@link Operators#onNextDropped(Object, Context) dropped}.
	 * <p>
	 * If this handler fails with an exception, that exception is {@link Operators#onErrorDropped(Throwable, Context) dropped} before the
	 * value is also dropped.
	 *
	 * @param value the value for which an emission was attempted (which will be automatically dropped afterwards)
	 */
	void doOnMalformedOnNext(T value) throws Throwable;

	/**
	 * Handle malformed {@link Subscriber#onError(Throwable)}, which means the sequence has already terminated
	 * via {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)}.
	 * Note that after this handler is invoked, the exception is automatically {@link Operators#onErrorDropped(Throwable, Context) dropped}.
	 * <p>
	 * If this handler fails with an exception, that exception is {@link Operators#onErrorDropped(Throwable, Context) dropped} before the
	 * original onError exception is also dropped.
	 *
	 * @param error the extraneous exception (which will be automatically dropped afterwards)
	 */
	void doOnMalformedOnError(Throwable error) throws Throwable;

	/**
	 * Handle malformed {@link Subscriber#onComplete()}, which means the sequence has already terminated
	 * via {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)}.
	 * <p>
	 * If this handler fails with an exception, that exception is {@link Operators#onErrorDropped(Throwable, Context) dropped}.
	 */
	void doOnMalformedOnComplete() throws Throwable;

	abstract class DefaultSequenceListener<T> implements SequenceListener<T> {

		@Override
		public void doFirst() throws Throwable {}

		@Override
		public void doFinally(SignalType terminationType) throws Throwable {}

		@Override
		public void doOnSubscription() throws Throwable {}

		@Override
		public void doOnRequest(long requested) throws Throwable {}

		@Override
		public void doOnCancel() throws Throwable {}

		@Override
		public void doOnNext(T value) throws Throwable {}

		@Override
		public void doOnComplete() throws Throwable {}

		@Override
		public void doOnError(Throwable error) throws Throwable {}

		@Override
		public void doAfterComplete() throws Throwable {}

		@Override
		public void doAfterError(Throwable error) throws Throwable {}

		@Override
		public void doOnMalformedOnNext(T value) throws Throwable {}

		@Override
		public void doOnMalformedOnComplete() throws Throwable {}

		@Override
		public void doOnMalformedOnError(Throwable error) throws Throwable {}
	}
}
