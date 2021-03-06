/*
 * Copyright 2002-2014 the original author or authors.
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

package org.springframework.integration.mongodb.store;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.integration.history.MessageHistory.NAME_PROPERTY;
import static org.springframework.integration.history.MessageHistory.TIMESTAMP_PROPERTY;
import static org.springframework.integration.history.MessageHistory.TYPE_PROPERTY;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.springframework.beans.BeansException;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.CustomConversions;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.integration.history.MessageHistory;
import org.springframework.integration.message.AdviceMessage;
import org.springframework.integration.message.MutableMessage;
import org.springframework.integration.store.AbstractMessageGroupStore;
import org.springframework.integration.store.MessageGroup;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.integration.store.MessageStore;
import org.springframework.integration.store.SimpleMessageGroup;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;


/**
 * An implementation of both the {@link MessageStore} and {@link MessageGroupStore}
 * strategies that relies upon MongoDB for persistence.
 *
 * @author Mark Fisher
 * @author Oleg Zhurakousky
 * @author Sean Brandt
 * @author Jodie StJohn
 * @author Gary Russell
 * @author Artem Bilan
 * @since 2.1
 */
public class MongoDbMessageStore extends AbstractMessageGroupStore
		implements MessageStore, BeanClassLoaderAware, ApplicationContextAware, InitializingBean {

	private final static String DEFAULT_COLLECTION_NAME = "messages";

	private final static String GROUP_ID_KEY = "_groupId";

	private final static String GROUP_COMPLETE_KEY = "_group_complete";

	private final static String LAST_RELEASED_SEQUENCE_NUMBER = "_last_released_sequence";

	private final static String GROUP_TIMESTAMP_KEY = "_group_timestamp";

	private final static String GROUP_UPDATE_TIMESTAMP_KEY = "_group_update_timestamp";

	private final static String CREATED_DATE = "_createdDate";


	private final MongoTemplate template;

	private final MessageReadingMongoConverter converter;

	private final String collectionName;

	private volatile ClassLoader classLoader = ClassUtils.getDefaultClassLoader();

	private ApplicationContext applicationContext;


	/**
	 * Create a MongoDbMessageStore using the provided {@link MongoDbFactory}.and the default collection name.
	 *
	 * @param mongoDbFactory The mongodb factory.
	 */
	public MongoDbMessageStore(MongoDbFactory mongoDbFactory) {
		this(mongoDbFactory, null);
	}

	/**
	 * Create a MongoDbMessageStore using the provided {@link MongoDbFactory} and collection name.
	 *
	 * @param mongoDbFactory The mongodb factory.
	 * @param collectionName The collection name.
	 */
	public MongoDbMessageStore(MongoDbFactory mongoDbFactory, String collectionName) {
		Assert.notNull(mongoDbFactory, "mongoDbFactory must not be null");
		this.converter = new MessageReadingMongoConverter(mongoDbFactory, new MongoMappingContext());
		this.template = new MongoTemplate(mongoDbFactory, this.converter);
		this.collectionName = (StringUtils.hasText(collectionName)) ? collectionName : DEFAULT_COLLECTION_NAME;
	}


	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		Assert.notNull(classLoader, "classLoader must not be null");
		this.classLoader = classLoader;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		if (this.applicationContext != null) {
			this.template.setApplicationContext(this.applicationContext);
			this.converter.setApplicationContext(this.applicationContext);
		}
		this.converter.afterPropertiesSet();
	}

	@Override
	public <T> Message<T> addMessage(Message<T> message) {
		Assert.notNull(message, "'message' must not be null");
		this.template.insert(new MessageWrapper(message), this.collectionName);
		return message;
	}

	@Override
	public Message<?> getMessage(UUID id) {
		Assert.notNull(id, "'id' must not be null");
		MessageWrapper messageWrapper = this.template.findOne(whereMessageIdIs(id), MessageWrapper.class, this.collectionName);
		return (messageWrapper != null) ? messageWrapper.getMessage() : null;
	}

	@Override
	@ManagedAttribute
	public long getMessageCount() {
		return this.template.getCollection(this.collectionName).getCount();
	}

	@Override
	public Message<?> removeMessage(UUID id) {
		Assert.notNull(id, "'id' must not be null");
		MessageWrapper messageWrapper =  this.template.findAndRemove(whereMessageIdIs(id), MessageWrapper.class, this.collectionName);
		return (messageWrapper != null) ? messageWrapper.getMessage() : null;
	}

	@Override
	public MessageGroup getMessageGroup(Object groupId) {
		Assert.notNull(groupId, "'groupId' must not be null");
		List<MessageWrapper> messageWrappers = this.template.find(whereGroupIdIs(groupId), MessageWrapper.class, this.collectionName);
		List<Message<?>> messages = new ArrayList<Message<?>>();
		long timestamp = 0;
		long lastmodified = 0;
		int lastReleasedSequenceNumber = 0;
		boolean completeGroup = false;
		if (messageWrappers.size() > 0){
			MessageWrapper messageWrapper = messageWrappers.get(0);
			timestamp = messageWrapper.get_Group_timestamp();
			lastmodified = messageWrapper.get_Group_update_timestamp();
			completeGroup = messageWrapper.get_Group_complete();
			lastReleasedSequenceNumber = messageWrapper.get_LastReleasedSequenceNumber();
		}

		for (MessageWrapper messageWrapper : messageWrappers) {
			messages.add(messageWrapper.getMessage());
		}

		SimpleMessageGroup messageGroup = new SimpleMessageGroup(messages, groupId, timestamp, completeGroup);
		messageGroup.setLastModified(lastmodified);
		if (lastReleasedSequenceNumber > 0){
			messageGroup.setLastReleasedMessageSequenceNumber(lastReleasedSequenceNumber);
		}

		return messageGroup;
	}

	@Override
	public MessageGroup addMessageToGroup(Object groupId, Message<?> message) {
		Assert.notNull(groupId, "'groupId' must not be null");
		Assert.notNull(message, "'message' must not be null");
		MessageGroup messageGroup = this.getMessageGroup(groupId);

		long messageGroupTimestamp = messageGroup.getTimestamp();
		long lastModified = messageGroup.getLastModified();

		if (messageGroupTimestamp == 0){
			messageGroupTimestamp = System.currentTimeMillis();
			lastModified = messageGroupTimestamp;
		}
		else {
			lastModified = System.currentTimeMillis();
		}

		MessageWrapper wrapper = new MessageWrapper(message);
		wrapper.set_GroupId(groupId);
		wrapper.set_Group_timestamp(messageGroupTimestamp);
		wrapper.set_Group_update_timestamp(lastModified);
		wrapper.set_Group_complete(messageGroup.isComplete());
		wrapper.set_LastReleasedSequenceNumber(messageGroup.getLastReleasedMessageSequenceNumber());

		this.template.insert(wrapper, this.collectionName);
		return this.getMessageGroup(groupId);
	}

	@Override
	public MessageGroup removeMessageFromGroup(Object groupId, Message<?> messageToRemove) {
		Assert.notNull(groupId, "'groupId' must not be null");
		Assert.notNull(messageToRemove, "'messageToRemove' must not be null");
		this.template.findAndRemove(whereMessageIdIsAndGroupIdIs(
				messageToRemove.getHeaders().getId(), groupId), MessageWrapper.class, this.collectionName);
		this.updateGroup(groupId);
		return this.getMessageGroup(groupId);
	}

	@Override
	public void removeMessageGroup(Object groupId) {
		List<MessageWrapper> messageWrappers = this.template.find(whereGroupIdIs(groupId), MessageWrapper.class, this.collectionName);
		for (MessageWrapper messageWrapper : messageWrappers) {
			this.removeMessageFromGroup(groupId, messageWrapper.getMessage());
		}
	}

	@Override
	public Iterator<MessageGroup> iterator() {
		List<MessageWrapper> groupedMessages = this.template.find(whereGroupIdExists(), MessageWrapper.class, this.collectionName);
		Map<Object, MessageGroup> messageGroups = new HashMap<Object, MessageGroup>();
		for (MessageWrapper groupedMessage : groupedMessages) {
			Object groupId = groupedMessage.get_GroupId();
			if (!messageGroups.containsKey(groupId)) {
				messageGroups.put(groupId, this.getMessageGroup(groupId));
			}
		}
		return messageGroups.values().iterator();
	}

	@Override
	public void completeGroup(Object groupId) {
		Update update = Update.update(GROUP_COMPLETE_KEY, true);
		Query q = whereGroupIdIs(groupId);
		this.template.updateFirst(q, update, this.collectionName);
		this.updateGroup(groupId);
	}

	@Override
	public void setLastReleasedSequenceNumberForGroup(Object groupId, int sequenceNumber) {
		Update update = Update.update(LAST_RELEASED_SEQUENCE_NUMBER, sequenceNumber);
		Query q = whereGroupIdIs(groupId);
		this.template.updateFirst(q, update, this.collectionName);
		this.updateGroup(groupId);
	}

	@Override
	public Message<?> pollMessageFromGroup(Object groupId) {
		Assert.notNull(groupId, "'groupId' must not be null");
		MessageWrapper messageWrapper = this.template.findAndRemove(whereGroupIdIsOrdered(groupId), MessageWrapper.class, this.collectionName);
		Message<?> message = null;
		if (messageWrapper != null) {
			message = messageWrapper.getMessage();
		}
		this.updateGroup(groupId);
		return message;
	}

	@Override
	public int messageGroupSize(Object groupId) {
		long lCount = this.template.count(new Query(where(GROUP_ID_KEY).is(groupId)), this.collectionName);
		Assert.isTrue(lCount <= Integer.MAX_VALUE, "Message count is out of Integer's range");
		return (int) lCount;
	}

	/*
	 * Common Queries
	 */

	private static Query whereMessageIdIs(UUID id) {
		return new Query(where("headers.id._value").is(id.toString()));
	}

	private static Query whereMessageIdIsAndGroupIdIs(UUID id, Object groupId) {
		return new Query(where("headers.id._value").is(id.toString()).and(GROUP_ID_KEY).is(groupId));
	}

	private static Query whereGroupIdIs(Object groupId) {
		Query q = new Query(where(GROUP_ID_KEY).is(groupId));
		q.with(new Sort(Direction.DESC, GROUP_UPDATE_TIMESTAMP_KEY));
		return q;
	}

	private static Query whereGroupIdExists() {
		return new Query(where(GROUP_ID_KEY).exists(true));
	}

	private static Query whereGroupIdIsOrdered(Object groupId) {
		Query q = new Query(where(GROUP_ID_KEY).is(groupId)).limit(1);
		q.with(new Sort(Direction.ASC, CREATED_DATE));
		return q;
	}

	private void updateGroup(Object groupId) {
		Update update = Update.update(GROUP_UPDATE_TIMESTAMP_KEY, System.currentTimeMillis());
		Query q = whereGroupIdIs(groupId);
		this.template.updateFirst(q, update, this.collectionName);
	}


	/**
	 * Custom implementation of the {@link MappingMongoConverter} strategy.
	 */
	private class MessageReadingMongoConverter extends MappingMongoConverter {

		public MessageReadingMongoConverter(MongoDbFactory mongoDbFactory,
				MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext) {
			super(mongoDbFactory, mappingContext);
		}

		@Override
		public void afterPropertiesSet() {
			List<Converter<?, ?>> customConverters = new ArrayList<Converter<?,?>>();
			customConverters.add(new UuidToDBObjectConverter());
			customConverters.add(new DBObjectToUUIDConverter());
			customConverters.add(new MessageHistoryToDBObjectConverter());
			customConverters.add(new DBObjectToGenericMessageConverter());
			customConverters.add(new DBObjectToMutableMessageConverter());
			customConverters.add(new DBObjectToErrorMessageConverter());
			customConverters.add(new DBObjectToAdviceMessageConverter());
			customConverters.add(new ThrowableToBytesConverter());
			this.setCustomConversions(new CustomConversions(customConverters));
			super.afterPropertiesSet();
		}

		@Override
		public void write(Object source, DBObject target) {
			Assert.isInstanceOf(MessageWrapper.class, source);

			target.put(CREATED_DATE, System.currentTimeMillis());

			super.write(source, target);
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public <S> S read(Class<S> clazz, DBObject source) {
			if (!MessageWrapper.class.equals(clazz)) {
				return super.read(clazz, source);
			}
			if (source != null) {
				Message<?> message = null;
				Object messageType = source.get("_messageType");
				if (messageType == null) {
					messageType = GenericMessage.class.getName();
				}
				try {
					message = (Message<?>) this.read(ClassUtils.forName(messageType.toString(), classLoader), source);
				}
				catch (ClassNotFoundException e) {
					throw new IllegalStateException("failed to load class: " + messageType, e);
				}

				Long groupTimestamp = (Long)source.get(GROUP_TIMESTAMP_KEY);
				Long lastModified = (Long)source.get(GROUP_UPDATE_TIMESTAMP_KEY);
				Integer lastReleasedSequenceNumber = (Integer)source.get(LAST_RELEASED_SEQUENCE_NUMBER);
				Boolean completeGroup = (Boolean)source.get(GROUP_COMPLETE_KEY);

				MessageWrapper wrapper = new MessageWrapper(message);

				if (source.containsField(GROUP_ID_KEY)){
					wrapper.set_GroupId(source.get(GROUP_ID_KEY));
				}
				if (groupTimestamp != null){
					wrapper.set_Group_timestamp(groupTimestamp);
				}
				if (lastModified != null){
					wrapper.set_Group_update_timestamp(lastModified);
				}
				if (lastReleasedSequenceNumber != null){
					wrapper.set_LastReleasedSequenceNumber(lastReleasedSequenceNumber);
				}

				if (completeGroup != null){
					wrapper.set_Group_complete(completeGroup);
				}

				return (S) wrapper;
			}
			return null;
		}

		private Map<String, Object> normalizeHeaders(Map<String, Object> headers) {
			Map<String, Object> normalizedHeaders = new HashMap<String, Object>();
			for (String headerName : headers.keySet()) {
				Object headerValue = headers.get(headerName);
				if (headerValue instanceof DBObject) {
					DBObject source = (DBObject) headerValue;
					try {
						Class<?> typeClass = null;
						if (source.containsField("_class")) {
							Object type = source.get("_class");
							typeClass = ClassUtils.forName(type.toString(), classLoader);
						}
						else if (source instanceof BasicDBList) {
							typeClass = List.class;
						}
						else {
							throw new IllegalStateException("Unsupported 'DBObject' type: " + source.getClass());
						}
						normalizedHeaders.put(headerName, super.read(typeClass, source));
					}
					catch (Exception e) {
						logger.warn("Header '" + headerName + "' could not be deserialized.", e);
					}
				}
				else {
					normalizedHeaders.put(headerName, headerValue);
				}
			}
			return normalizedHeaders;
		}

		private Object extractPayload(DBObject source) {
			Object payload = source.get("payload");
			if (payload instanceof DBObject) {
				DBObject payloadObject = (DBObject) payload;
				Object payloadType = payloadObject.get("_class");
				try {
					Class<?> payloadClass = ClassUtils.forName(payloadType.toString(), classLoader);
					payload = this.read(payloadClass, payloadObject);
				}
				catch (Exception e) {
					throw new IllegalStateException("failed to load class: " + payloadType, e);
				}
			}
			return payload;
		}

	}

	@SuppressWarnings("unchecked")
	private static void enhanceHeaders(MessageHeaders messageHeaders, Map<String, Object> headers) {
		Map<String, Object> innerMap = (Map<String, Object>) new DirectFieldAccessor(messageHeaders).getPropertyValue("headers");
		// using reflection to set ID and TIMESTAMP since they are immutable through MessageHeaders
		innerMap.put(MessageHeaders.ID, headers.get(MessageHeaders.ID));
		innerMap.put(MessageHeaders.TIMESTAMP, headers.get(MessageHeaders.TIMESTAMP));
	}


	private static class UuidToDBObjectConverter implements Converter<UUID, DBObject> {
		@Override
		public DBObject convert(UUID source) {
			BasicDBObject dbObject = new BasicDBObject();
			dbObject.put("_value", source.toString());
			dbObject.put("_class", source.getClass().getName());
			return dbObject;
		}
	}

	private static class DBObjectToUUIDConverter implements Converter<DBObject, UUID> {
		@Override
		public UUID convert(DBObject source) {
			return UUID.fromString((String) source.get("_value"));
		}
	}


	private static class MessageHistoryToDBObjectConverter implements Converter<MessageHistory,DBObject> {

		@Override
		public DBObject convert(MessageHistory source) {
			BasicDBObject obj = new BasicDBObject();
			obj.put("_class", MessageHistory.class.getName());
			BasicDBList dbList = new BasicDBList();
			for (Properties properties : source) {
				BasicDBObject dbo = new BasicDBObject();
				dbo.put(NAME_PROPERTY, properties.getProperty(NAME_PROPERTY));
				dbo.put(TYPE_PROPERTY, properties.getProperty(TYPE_PROPERTY));
				dbo.put(TIMESTAMP_PROPERTY, properties.getProperty(TIMESTAMP_PROPERTY));
				dbList.add(dbo);
			}
			obj.put("components", dbList);
			return obj;
		}
	}

	private class DBObjectToGenericMessageConverter implements Converter<DBObject, GenericMessage<?>> {

		@Override

		public GenericMessage<?> convert(DBObject source) {
			@SuppressWarnings("unchecked")
			Map<String, Object> headers = MongoDbMessageStore.this.converter.normalizeHeaders((Map<String, Object>) source.get("headers"));

			GenericMessage<?> message = new GenericMessage<Object>(MongoDbMessageStore.this.converter.extractPayload(source), headers);
			enhanceHeaders(message.getHeaders(), headers);
			return message;
		}

	}

	private class DBObjectToMutableMessageConverter implements Converter<DBObject, MutableMessage<?>> {

		@Override
		public MutableMessage<?> convert(DBObject source) {
			@SuppressWarnings("unchecked")
			Map<String, Object> headers = MongoDbMessageStore.this.converter.normalizeHeaders((Map<String, Object>) source.get("headers"));

			return new MutableMessage<Object>(MongoDbMessageStore.this.converter.extractPayload(source), headers);
		}

	}

	private class DBObjectToAdviceMessageConverter implements Converter<DBObject, AdviceMessage> {

		@Override
		public AdviceMessage convert(DBObject source) {
			@SuppressWarnings("unchecked")
			Map<String, Object> headers = MongoDbMessageStore.this.converter.normalizeHeaders((Map<String, Object>) source.get("headers"));

			Message<?> inputMessage = null;

			if (source.get("inputMessage") != null) {
				DBObject inputMessageObject = (DBObject) source.get("inputMessage");
				Object inputMessageType = inputMessageObject.get("_class");
				try {
					Class<?> messageClass = ClassUtils.forName(inputMessageType.toString(), classLoader);
					inputMessage = (Message<?>) MongoDbMessageStore.this.converter.read(messageClass, inputMessageObject);
				}
				catch (Exception e) {
					throw new IllegalStateException("failed to load class: " + inputMessageType, e);
				}
			}

			AdviceMessage message = new AdviceMessage(MongoDbMessageStore.this.converter.extractPayload(source), headers, inputMessage);
			enhanceHeaders(message.getHeaders(), headers);

			return message;
		}

	}

	private class DBObjectToErrorMessageConverter implements Converter<DBObject, ErrorMessage> {

		private final Converter<byte[], Object> deserializingConverter = new DeserializingConverter();

		@Override
		public ErrorMessage convert(DBObject source) {
			@SuppressWarnings("unchecked")
			Map<String, Object> headers = MongoDbMessageStore.this.converter.normalizeHeaders((Map<String, Object>) source.get("headers"));

			Object payload = this.deserializingConverter.convert((byte[]) source.get("payload"));
			ErrorMessage message = new ErrorMessage((Throwable) payload, headers);
			enhanceHeaders(message.getHeaders(), headers);

			return message;
		}

	}

	@WritingConverter
	private class ThrowableToBytesConverter implements Converter<Throwable, byte[]> {

		private final Converter<Object, byte[]> serializingConverter = new SerializingConverter();

		@Override
		public byte[] convert(Throwable source) {
			return serializingConverter.convert(source);
		}

	}


	/**
	 * Wrapper class used for storing Messages in MongoDB along with their "group" metadata.
	 */
	private static final class MessageWrapper {

		/*
		 * Needed as a persistence property to suppress 'Cannot determine IsNewStrategy' MappingException
		 * when the application context is configured with auditing. The document is not
		 * currently Auditable.
		 */
		@SuppressWarnings("unused")
		@Id
		private String _id;

		private volatile Object _groupId;

		@Transient
		private final Message<?> message;

		@SuppressWarnings("unused")
		private final String _messageType;

		private final Object payload;

		@SuppressWarnings("unused")
		private final Map<String, ?> headers;

		private final Message<?> inputMessage;

		private volatile long _group_timestamp;

		private volatile long _group_update_timestamp;

		private volatile int _last_released_sequence;

		private volatile boolean _group_complete;

		public MessageWrapper(Message<?> message) {
			Assert.notNull(message, "'message' must not be null");
			this.message = message;
			this._messageType = message.getClass().getName();
			this.payload = message.getPayload();
			this.headers = message.getHeaders();
			if (message instanceof AdviceMessage) {
				this.inputMessage = ((AdviceMessage) message).getInputMessage();
			}
			else {
				this.inputMessage = null;
			}
		}

		public int get_LastReleasedSequenceNumber() {
			return _last_released_sequence;
		}

		public long get_Group_timestamp() {
			return _group_timestamp;
		}

		public boolean get_Group_complete() {
			return _group_complete;
		}

		public Object get_GroupId() {
			return _groupId;
		}

		public Message<?> getMessage() {
			return message;
		}

		public void set_GroupId(Object groupId) {
			this._groupId = groupId;
		}

		public void set_Group_timestamp(long groupTimestamp) {
			this._group_timestamp = groupTimestamp;
		}

		public long get_Group_update_timestamp() {
			return _group_update_timestamp;
		}

		public void set_Group_update_timestamp(long lastModified) {
			this._group_update_timestamp = lastModified;
		}

		public void set_LastReleasedSequenceNumber(int lastReleasedSequenceNumber) {
			this._last_released_sequence = lastReleasedSequenceNumber;
		}

		public void set_Group_complete(boolean completedGroup) {
			this._group_complete = completedGroup;
		}
	}
}
