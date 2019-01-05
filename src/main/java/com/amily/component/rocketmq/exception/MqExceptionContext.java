package com.amily.component.rocketmq.exception;

/**
 * @author lizhuo
 * @since 2019/1/5 下午9:24
 **/
public class MqExceptionContext extends Throwable {

    private String messageId;
    private String topic;
    private MqClientException exception;

    public MqExceptionContext() {
    }

    public String getMessageId() {
        return this.messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getTopic() {
        return this.topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public MqClientException getException() {
        return this.exception;
    }

    public void setException(MqClientException exception) {
        this.exception = exception;
    }
}
