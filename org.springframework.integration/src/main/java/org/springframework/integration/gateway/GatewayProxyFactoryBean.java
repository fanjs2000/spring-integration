/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.gateway;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.Lifecycle;
import org.springframework.integration.annotation.Gateway;
import org.springframework.integration.core.Message;
import org.springframework.integration.core.MessageChannel;
import org.springframework.integration.endpoint.AbstractEndpoint;
import org.springframework.integration.message.MethodParameterMessageMapper;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Generates a proxy for the provided service interface to enable interaction
 * with messaging components without application code being aware of them.
 * 
 * @author Mark Fisher
 */
public class GatewayProxyFactoryBean extends AbstractEndpoint implements FactoryBean, MethodInterceptor, BeanClassLoaderAware {

	private volatile Class<?> serviceInterface;

	private volatile MessageChannel defaultRequestChannel;

	private volatile MessageChannel defaultReplyChannel;

	private volatile long defaultRequestTimeout = -1;

	private volatile long defaultReplyTimeout = -1;

	private volatile TypeConverter typeConverter = new SimpleTypeConverter();

	private volatile ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	private volatile Object serviceProxy;

	private final Map<Method, MessagingGateway> gatewayMap = new HashMap<Method, MessagingGateway>();

	private volatile boolean initialized;

	private final Object initializationMonitor = new Object();


	public void setServiceInterface(Class<?> serviceInterface) {
		if (serviceInterface != null && !serviceInterface.isInterface()) {
			throw new IllegalArgumentException("'serviceInterface' must be an interface");
		}
		this.serviceInterface = serviceInterface;
	}

	/**
	 * Set the default request channel.
	 * 
	 * @param defaulRequestChannel the channel to which request messages will
	 * be sent if no request channel has been configured with an annotation
	 */
	public void setDefaultRequestChannel(MessageChannel defaultRequestChannel) {
		this.defaultRequestChannel = defaultRequestChannel;
	}

	/**
	 * Set the default reply channel. If no default reply channel is provided,
	 * and no reply channel is configured with annotations, an anonymous,
	 * temporary channel will be used for handling replies.
	 * 
	 * @param replyChannel the channel from which reply messages will be
	 * received if no reply channel has been configured with an annotation
	 */
	public void setDefaultReplyChannel(MessageChannel defaultReplyChannel) {
		this.defaultReplyChannel = defaultReplyChannel;
	}

	/**
	 * Set the default timeout value for sending request messages. If not
	 * explicitly configured with an annotation, this value will be used.
	 * 
	 * @param defaultRequestTimeout the timeout value in milliseconds
	 */
	public void setDefaultRequestTimeout(long defaultRequestTimeout) {
		this.defaultRequestTimeout = defaultRequestTimeout;
	}

	/**
	 * Set the default timeout value for receiving reply messages. If not
	 * explicitly configured with an annotation, this value will be used.
	 * 
	 * @param defaultReplyTimeout the timeout value in milliseconds
	 */
	public void setDefaultReplyTimeout(long defaultReplyTimeout) {
		this.defaultReplyTimeout = defaultReplyTimeout;
	}

	public void setTypeConverter(TypeConverter typeConverter) {
		Assert.notNull(typeConverter, "typeConverter must not be null");
		this.typeConverter = typeConverter;
	}

	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
	}

	@Override
	protected void onInit() throws Exception {
		synchronized (this.initializationMonitor) {
			if (this.initialized) {
				return;
			}
			if (this.serviceInterface == null) {
				throw new IllegalArgumentException("'serviceInterface' must not be null");
			}
			Method[] methods = this.serviceInterface.getDeclaredMethods();
			for (Method method : methods) {
				MessagingGateway gateway = this.createGatewayForMethod(method);
				this.gatewayMap.put(method, gateway);
			}
			this.serviceProxy = new ProxyFactory(this.serviceInterface, this).getProxy(this.beanClassLoader);
			this.start();
			this.initialized = true;
		}
	}

	public Object getObject() throws Exception {
		return this.serviceProxy;
	}

	public Class<?> getObjectType() {
		return this.serviceInterface;
	}

	public boolean isSingleton() {
		return true;
	}

	public Object invoke(MethodInvocation invocation) throws Throwable {
		Method method = invocation.getMethod();
		if (AopUtils.isToStringMethod(method)) {
			return "gateway proxy for service interface [" + this.serviceInterface + "]";
		}
		if (method.getDeclaringClass().equals(this.serviceInterface)) {
			try {
				return this.invokeGatewayMethod(invocation);
			}
			catch (Exception e) {
				rethrowExceptionInThrowsClauseIfPossible(e, invocation.getMethod());
			}
		}
		return invocation.proceed();
	}

	private Object invokeGatewayMethod(MethodInvocation invocation) throws Exception {
		if (!this.initialized) {
			this.afterPropertiesSet();
		}
		Method method = invocation.getMethod();
		MessagingGateway gateway = this.gatewayMap.get(method);
		Class<?> returnType = method.getReturnType();
		boolean isReturnTypeMessage = Message.class.isAssignableFrom(returnType);
		boolean shouldReply = returnType != void.class;
		int paramCount = method.getParameterTypes().length;
		Object response = null;
		if (paramCount == 0) {
			if (shouldReply) {
				if (isReturnTypeMessage) {
					return gateway.receive();
				}
				response = gateway.receive();
			}
		}
		else {
			Object[] args = invocation.getArguments();
			if (shouldReply) {
				response = isReturnTypeMessage ? gateway.sendAndReceiveMessage(args) : gateway.sendAndReceive(args);
			}
			else {
				gateway.send(args);
				response = null;
			}
		}
		return (response != null) ? this.typeConverter.convertIfNecessary(response, returnType) : null;
	}

	private void rethrowExceptionInThrowsClauseIfPossible(Throwable originalException, Method method) throws Throwable {
		List<Class<?>> exceptionTypes = Arrays.asList(method.getExceptionTypes());
		Throwable t = originalException;
		while (t != null) {
			if (exceptionTypes.contains(t.getClass())) {
				throw t;
			}
			t = t.getCause();
		}
		throw originalException;
	}

	private MessagingGateway createGatewayForMethod(Method method) throws Exception {
		SimpleMessagingGateway gateway = new SimpleMessagingGateway(
				new MethodParameterMessageMapper(method), new SimpleMessageMapper());
		if (this.getTaskScheduler() != null) {
			gateway.setTaskScheduler(this.getTaskScheduler());
		}
		Gateway gatewayAnnotation = method.getAnnotation(Gateway.class);
		MessageChannel requestChannel = this.defaultRequestChannel;
		MessageChannel replyChannel = this.defaultReplyChannel;
		long requestTimeout = this.defaultRequestTimeout;
		long replyTimeout = this.defaultReplyTimeout;
		if (gatewayAnnotation != null) {
			Assert.state(this.getChannelResolver() != null, "ChannelResolver is required");
			String requestChannelName = gatewayAnnotation.requestChannel();
			if (StringUtils.hasText(requestChannelName)) {
				requestChannel = this.getChannelResolver().resolveChannelName(requestChannelName);
				Assert.notNull(requestChannel, "failed to resolve request channel '" + requestChannelName + "'");
			}
			String replyChannelName = gatewayAnnotation.replyChannel();
			if (StringUtils.hasText(replyChannelName)) {
				replyChannel = this.getChannelResolver().resolveChannelName(replyChannelName);
				Assert.notNull(replyChannel, "failed to resolve reply channel '" + replyChannelName + "'");
			}
			requestTimeout = gatewayAnnotation.requestTimeout();
			replyTimeout = gatewayAnnotation.replyTimeout();
		}
		gateway.setRequestChannel(requestChannel);
		gateway.setReplyChannel(replyChannel);
		gateway.setRequestTimeout(requestTimeout);
		gateway.setReplyTimeout(replyTimeout);
		if (this.getBeanFactory() != null) {
			gateway.setBeanFactory(this.getBeanFactory());
		}
		return gateway;
	}

	// Lifecycle implementation

	@Override // guarded by super#lifecycleLock
	protected void doStart() {
		for (MessagingGateway gateway : this.gatewayMap.values()) {
			if (gateway instanceof Lifecycle) {
				((Lifecycle) gateway).start();
			}
		}
	}

	@Override // guarded by super#lifecycleLock
	protected void doStop() {
		for (MessagingGateway gateway : this.gatewayMap.values()) {
			if (gateway instanceof Lifecycle) {
				((Lifecycle) gateway).stop();
			}
		}
	}

}