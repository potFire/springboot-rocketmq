package com.amily.mq;

import com.amily.annotation.RocketMqListener;
import com.amily.annotation.RocketMqOrderListener;
import com.amily.config.RocketMqProperties;
import com.amily.enums.MqAction;
import com.amily.util.GeneratorId;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.context.ApplicationContext;

import java.util.Map;


/**
 * @author lizhuo
 * @since 2019/1/4 下午10:15
 **/
public class RocketMqConsumer {
    private static Logger LOGGER = LogManager.getLogger();
    public ApplicationContext context;
    private volatile boolean init = false;
    private RocketMqProperties configuration;
    private Map<String, DefaultMQPushConsumer> consumerMap;


    public RocketMqConsumer(RocketMqProperties configuration, ApplicationContext context) {
        this.context = context;
        this.configuration = configuration;
    }


    public synchronized void start() throws Exception {
        if (this.init) {
            LOGGER.warn("请不要重复初始化RocketMQ消费者");
            return;
        }
        this.consumerMap = Maps.newConcurrentMap();
        initializeConsumer(this.consumerMap);
        init = true;
    }

    /**
     * 初始化消费者，同项目内不允许对同一个topic多次加载
     *
     * @param map 存储消费者
     */
    private void initializeConsumer(Map<String, DefaultMQPushConsumer> map) throws Exception {

        Map<String, String> topicMap = Maps.newHashMap();

        Map<String, String> consumerGroupMap = Maps.newHashMap();
        //初始化普通消息消费者
        initializeConcurrentlyConsumer(map, topicMap, consumerGroupMap);

        //初始化有序消息消费者
        initializeOrderConsumer(map, topicMap, consumerGroupMap);

        consumerMap.forEach((key, consumer) -> {
            try {
                String instanceName = System.currentTimeMillis() + GeneratorId.nextFormatId();
                consumer.setInstanceName(instanceName);
                consumer.start();
                LOGGER.info(String.format("自建RocketMQ 成功加载 Topic-tag:%s", key));
            } catch (MQClientException e) {
                LOGGER.error(String.format("自建RocketMQ 加载失败 Topic-tag:%s", key), e);
                throw new RuntimeException(e.getMessage(), e);
            }
        });
        LOGGER.info("--------------成功初始化所有消费者到自建mq--------------");

    }

    /**
     * 初始化普通消息消费者
     *
     * @param map
     * @throws MQClientException
     */
    private void initializeConcurrentlyConsumer(Map<String, DefaultMQPushConsumer> map, Map<String, String> topicMap, Map<String, String> consumerGroupMap) throws MQClientException {
        Map<String, Object> beansWithAnnotationMap = context.getBeansWithAnnotation(RocketMqListener.class);
        for (Map.Entry<String, Object> entry : beansWithAnnotationMap.entrySet()) {
            // 获取到实例对象的class信息
            Class<?> classIns = entry.getValue().getClass();
            RocketMqListener rocketMqListenerAnnotaion = classIns.getDeclaredAnnotation(RocketMqListener.class);
            String topic = rocketMqListenerAnnotaion.topic();
            String tag = rocketMqListenerAnnotaion.tag();
            String consumerGroup = rocketMqListenerAnnotaion.consumerGroup();
            validate(topicMap, consumerGroupMap, classIns, topic, consumerGroup);

            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
            //设置名称服务器地址
            consumer.setNamesrvAddr(this.configuration.getNamesrvAddr());
            consumer.subscribe(topic, tag);
            //注册消费回调
            consumer.registerMessageListener((MessageListenerConcurrently) (msgList, context) -> {

                for (MessageExt msg : msgList) {
                    System.out.println("=======msg=" + msg);
                    MessageListener listener = (MessageListener) entry.getValue();
                    MqAction action = listener.consume(msg, context);
                    switch (action) {
                        case ReconsumeLater:
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        default:
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });

            topicMap.put(topic, classIns.getSimpleName());
            consumerGroupMap.put(consumerGroup, classIns.getSimpleName());
            map.put(String.format("%s-%s", topic, tag), consumer);
        }
    }

    private void validate(Map<String, String> topicMap, Map<String, String> consumerGroupMap, Class<?> classIns, String topic, String consumerGroup) {
        if (StringUtils.isBlank(topic)) {
            throw new RuntimeException(classIns.getSimpleName() + ":topic不能为空");
        }
        if (StringUtils.isBlank(consumerGroup)) {
            throw new RuntimeException(classIns.getSimpleName() + ":consumerGroup不能为空");
        }

        if (topicMap.containsKey(topic)) {
            throw new RuntimeException(String.format("Topic:%s 已经由%s监听 请勿重复监听同一Topic", topic, classIns.getSimpleName()));
        }

        if (consumerGroupMap.containsKey(consumerGroup)) {
            throw new RuntimeException(String.format("consumerGroup:%s 已经由%s监听 请勿重复监听同一consumerGroup", consumerGroup, classIns.getSimpleName()));
        }
    }

    /**
     * 初始化有序消息消费者
     *
     * @param map
     * @throws MQClientException
     */
    private void initializeOrderConsumer(Map<String, DefaultMQPushConsumer> map, Map<String, String> topicMap, Map<String, String> consumerGroupMap) throws MQClientException {
        Map<String, Object> beansWithAnnotationMap = context.getBeansWithAnnotation(RocketMqOrderListener.class);

        for (Map.Entry<String, Object> entry : beansWithAnnotationMap.entrySet()) {
            // 获取到实例对象的class信息
            Class<?> classIns = entry.getValue().getClass();
            RocketMqOrderListener rocketMqListenerAnnotaion = classIns.getDeclaredAnnotation(RocketMqOrderListener.class);
            String topic = rocketMqListenerAnnotaion.topic();
            String tag = rocketMqListenerAnnotaion.tag();
            String consumerGroup = rocketMqListenerAnnotaion.consumerGroup();
            validate(topicMap, consumerGroupMap, classIns, topic, consumerGroup);
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
            //设置名称服务器地址
            consumer.setNamesrvAddr(this.configuration.getNamesrvAddr());
            consumer.subscribe(topic, tag);
            //注册消费回调
            consumer.registerMessageListener((MessageListenerOrderly) (msgList, context) -> {

                for (MessageExt msg : msgList) {
                    System.out.println("=======order msg=" + msg);
                    MessageOrderListener listener = (MessageOrderListener) entry.getValue();
                    MqAction action = listener.consume(msg, context);
                    switch (action) {
                        case ReconsumeLater:
                            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                        default:
                    }
                }
                return ConsumeOrderlyStatus.SUCCESS;
            });

            topicMap.put(topic, classIns.getSimpleName());
            consumerGroupMap.put(consumerGroup, classIns.getSimpleName());
            map.put(String.format("%s-%s", topic, tag), consumer);
        }
    }
}
