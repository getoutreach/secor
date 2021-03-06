/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pinterest.secor.parser;

import com.pinterest.secor.common.SecorConfig;
import com.pinterest.secor.message.Message;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jayway.jsonpath.JsonPath;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import java.util.List;

/**
 * SplitByFieldMessageParser extracts event type field (specified by 'message.split.field.name')
 * and timestamp field (specified by 'message.timestamp.name')
 * from JSON data and splits data into multiple outputs by event type and then partitions each output by date.
 *
 * Caution: this parser doesn't support finalization of partitions.
 */
public class SplitByFieldMessageParser extends TimestampedMessageParser implements Partitioner {
    private static final Logger LOG = LoggerFactory.getLogger(SplitByFieldMessageParser.class);
    private final String mSplitFieldName;
    protected SimpleDateFormat outputFormatter;
    protected Object inputPattern;
    protected SimpleDateFormat inputFormatter;

    public SplitByFieldMessageParser(SecorConfig config) {
        super(config);

        mSplitFieldName = config.getMessageSplitFieldName();
        TimeZone timeZone = config.getTimeZone();
        inputPattern = mConfig.getMessageTimestampInputPattern();
        if (inputPattern != null){
            inputFormatter = new SimpleDateFormat(inputPattern.toString());
            inputFormatter.setTimeZone(timeZone);
        }
    }

    @Override
    public long extractTimestampMillis(Message message) throws Exception {
        throw new UnsupportedOperationException("Unsupported, use extractPartitions method instead");
    }

    @Override
    public String[] extractPartitions(Message message) throws Exception {
        JSONObject jsonObject = (JSONObject) JSONValue.parse(message.getPayload());
        if (jsonObject == null) {
            throw new RuntimeException("Failed to parse message as Json object");
        }

        String eventType = extractEventType(jsonObject);
        long timestampMillis = extractTimestampMillis(jsonObject);

        String[] timestampPartitions = generatePartitions(timestampMillis, mUsingHourly, mUsingMinutely);
        return (String[]) ArrayUtils.addAll(new String[]{eventType}, timestampPartitions);
    }

    @Override
    public String[] getFinalizedUptoPartitions(List<Message> lastMessages,
                                               List<Message> committedMessages) throws Exception {
        throw new UnsupportedOperationException("Partition finalization is not supported");
    }

    @Override
    public String[] getPreviousPartitions(String[] partitions) throws Exception {
        throw new UnsupportedOperationException("Partition finalization is not supported");
    }

    protected String extractEventType(JSONObject jsonObject) {
        String eventType = getAttribute(jsonObject, mSplitFieldName);
        if ( eventType == null ) {
            throw new RuntimeException("Could not find key " + mSplitFieldName + " in Json message");
        }
        return eventType;
    }

    protected long extractTimestampMillis(JSONObject jsonObject) {
        Object fieldValue = getJsonFieldValue(jsonObject);
        if (fieldValue != null && !fieldValue.toString().isEmpty()) {
            if (inputFormatter != null){
                String thing = "bad value";
                try {
                    return inputFormatter.parse(fieldValue.toString()).getTime();
                } catch (Exception e) {
                    LOG.warn("Impossible to convert date = {} with the input pattern = {}. Using date default = dt=1970-01-01",
                               fieldValue.toString(), inputPattern.toString());
                }
            }else{
                return toMillis(Double.valueOf(fieldValue.toString()).longValue());
            }
        }
        LOG.warn("Failed to extract timestamp from the message");
        return 0;
    }

    protected String getAttribute(JSONObject json, String path) {
        return JsonPath.read(json.toString(), path);
    }
}
