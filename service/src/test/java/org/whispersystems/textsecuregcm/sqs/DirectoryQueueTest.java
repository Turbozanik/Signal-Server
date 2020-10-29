/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.sqs;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.whispersystems.textsecuregcm.storage.Account;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(JUnitParamsRunner.class)
public class DirectoryQueueTest {

    private AmazonSQS      sqs;
    private DirectoryQueue directoryQueue;

    @Before
    public void setUp() {
        sqs            = mock(AmazonSQS.class);
        directoryQueue = new DirectoryQueue("sqs://test", sqs);
    }

    @Test
    @Parameters(method = "argumentsForTestRefreshRegisteredUser")
    public void testRefreshRegisteredUser(final boolean accountEnabled, final boolean accountDiscoverableByPhoneNumber, final String expectedAction) {
        final Account account = mock(Account.class);
        when(account.getNumber()).thenReturn("+18005556543");
        when(account.getUuid()).thenReturn(UUID.randomUUID());
        when(account.isEnabled()).thenReturn(accountEnabled);
        when(account.isDiscoverableByPhoneNumber()).thenReturn(accountDiscoverableByPhoneNumber);

        directoryQueue.refreshRegisteredUser(account);

        final ArgumentCaptor<SendMessageRequest> requestCaptor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(requestCaptor.capture());

        final Map<String, MessageAttributeValue> messageAttributes = requestCaptor.getValue().getMessageAttributes();
        assertEquals(new MessageAttributeValue().withDataType("String").withStringValue(expectedAction), messageAttributes.get("action"));
    }

    @SuppressWarnings("unused")
    private Object argumentsForTestRefreshRegisteredUser() {
        return new Object[] {
                new Object[] { true,  true,  "add"    },
                new Object[] { true,  false, "delete" },
                new Object[] { false, true,  "delete" },
                new Object[] { false, false, "delete" }
        };
    }
}
