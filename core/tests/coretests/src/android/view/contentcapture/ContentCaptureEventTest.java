/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureEvent.TYPE_CONTEXT_UPDATED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_FINISHED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_STARTED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_DISAPPEARED;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.os.Parcel;
import android.os.SystemClock;
import android.view.autofill.AutofillId;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;

/**
 * Unit test for {@link ContentCaptureEvent}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.ContentCaptureEventTest}
 */
@RunWith(JUnit4.class)
public class ContentCaptureEventTest {

    private static final long MY_EPOCH = SystemClock.uptimeMillis();

    // Not using @Mock because it's final - no need to be fancy here....
    private final ContentCaptureContext mClientContext = new ContentCaptureContext.Builder()
            .setAction("WHATEVER").build();

    @Test
    public void testSetAutofillId_null() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        assertThrows(NullPointerException.class, () -> event.setAutofillId(null));
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).isNull();
    }

    @Test
    public void testSetAutofillIds_null() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        assertThrows(NullPointerException.class, () -> event.setAutofillIds(null));
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).isNull();
    }

    @Test
    public void testAddAutofillId_null() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        assertThrows(NullPointerException.class, () -> event.addAutofillId(null));
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).isNull();
    }

    @Test
    public void testSetAutofillId() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        final AutofillId id = new AutofillId(108);
        event.setAutofillId(id);
        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getIds()).isNull();
    }

    @Test
    public void testSetAutofillIds() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        final AutofillId id = new AutofillId(108);
        final ArrayList<AutofillId> ids = new ArrayList<>(1);
        ids.add(id);
        event.setAutofillIds(ids);
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).containsExactly(id);
    }

    @Test
    public void testAddAutofillId() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        final AutofillId id1 = new AutofillId(108);
        event.addAutofillId(id1);
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).containsExactly(id1);

        final AutofillId id2 = new AutofillId(666);
        event.addAutofillId(id2);
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).containsExactly(id1, id2).inOrder();
    }

    @Test
    public void testAddAutofillId_afterSetId() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        final AutofillId id1 = new AutofillId(108);
        event.setAutofillId(id1);
        assertThat(event.getId()).isEqualTo(id1);
        assertThat(event.getIds()).isNull();

        final AutofillId id2 = new AutofillId(666);
        event.addAutofillId(id2);
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).containsExactly(id1, id2).inOrder();
    }

    @Test
    public void testAddAutofillId_afterSetIds() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_VIEW_DISAPPEARED);

        final AutofillId id1 = new AutofillId(108);
        final ArrayList<AutofillId> ids = new ArrayList<>(1);
        ids.add(id1);
        event.setAutofillIds(ids);
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).containsExactly(id1);

        final AutofillId id2 = new AutofillId(666);
        event.addAutofillId(id2);
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).containsExactly(id1, id2).inOrder();
    }

    @Test
    public void testSessionStarted_directly() {
        final ContentCaptureEvent event = newEventForSessionStarted();
        assertSessionStartedEvent(event);
    }

    @Test
    public void testSessionStarted_throughParcel() {
        final ContentCaptureEvent event = newEventForSessionStarted();
        final ContentCaptureEvent clone = cloneThroughParcel(event);
        assertSessionStartedEvent(clone);
    }

    private ContentCaptureEvent newEventForSessionStarted() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_SESSION_STARTED)
                .setClientContext(mClientContext)
                .setParentSessionId("108");
        assertThat(event).isNotNull();
        return event;
    }

    private void assertSessionStartedEvent(ContentCaptureEvent event) {
        assertThat(event.getType()).isEqualTo(TYPE_SESSION_STARTED);
        assertThat(event.getEventTime()).isAtLeast(MY_EPOCH);
        assertThat(event.getSessionId()).isEqualTo("42");
        assertThat(event.getParentSessionId()).isEqualTo("108");
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).isNull();
        assertThat(event.getText()).isNull();
        assertThat(event.getViewNode()).isNull();
        final ContentCaptureContext clientContext = event.getContentCaptureContext();
        assertThat(clientContext).isNotNull();
        assertThat(clientContext.getAction()).isEqualTo("WHATEVER");
    }

    @Test
    public void testSessionFinished_directly() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_SESSION_FINISHED)
                .setParentSessionId("108");
        assertThat(event).isNotNull();
        assertSessionFinishedEvent(event);
    }

    @Test
    public void testSessionFinished_throughParcel() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_SESSION_FINISHED)
                .setClientContext(mClientContext) // should not be writting to parcel
                .setParentSessionId("108");
        assertThat(event).isNotNull();
        final ContentCaptureEvent clone = cloneThroughParcel(event);
        assertSessionFinishedEvent(clone);
    }

    private void assertSessionFinishedEvent(ContentCaptureEvent event) {
        assertThat(event.getType()).isEqualTo(TYPE_SESSION_FINISHED);
        assertThat(event.getEventTime()).isAtLeast(MY_EPOCH);
        assertThat(event.getSessionId()).isEqualTo("42");
        assertThat(event.getParentSessionId()).isEqualTo("108");
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).isNull();
        assertThat(event.getText()).isNull();
        assertThat(event.getViewNode()).isNull();
        assertThat(event.getContentCaptureContext()).isNull();
    }


    @Test
    public void testContextUpdated_directly() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_CONTEXT_UPDATED)
                .setClientContext(mClientContext);
        assertThat(event).isNotNull();
        assertContextUpdatedEvent(event);
    }

    @Test
    public void testContextUpdated_throughParcel() {
        final ContentCaptureEvent event = new ContentCaptureEvent("42", TYPE_CONTEXT_UPDATED)
                .setClientContext(mClientContext);
        assertThat(event).isNotNull();
        final ContentCaptureEvent clone = cloneThroughParcel(event);
        assertContextUpdatedEvent(clone);
    }

    private void assertContextUpdatedEvent(ContentCaptureEvent event) {
        assertThat(event.getType()).isEqualTo(TYPE_CONTEXT_UPDATED);
        assertThat(event.getEventTime()).isAtLeast(MY_EPOCH);
        assertThat(event.getSessionId()).isEqualTo("42");
        assertThat(event.getParentSessionId()).isNull();
        assertThat(event.getId()).isNull();
        assertThat(event.getIds()).isNull();
        assertThat(event.getText()).isNull();
        assertThat(event.getViewNode()).isNull();
        final ContentCaptureContext clientContext = event.getContentCaptureContext();
        assertThat(clientContext).isNotNull();
        assertThat(clientContext.getAction()).isEqualTo("WHATEVER");
    }

    // TODO(b/123036895): add test for all events type (right now we're just testing the 3 types
    // that use logic to write to parcel

    private ContentCaptureEvent cloneThroughParcel(ContentCaptureEvent event) {
        Parcel parcel = Parcel.obtain();

        try {
            // Write to parcel
            parcel.setDataPosition(0); // Sanity / paranoid check
            event.writeToParcel(parcel, 0);

            // Read from parcel
            parcel.setDataPosition(0);
            ContentCaptureEvent clone = ContentCaptureEvent.CREATOR.createFromParcel(parcel);
            assertThat(clone).isNotNull();
            return clone;
        } finally {
            parcel.recycle();
        }
    }

}
