package com.vertispan.draw.connected.client.lib;

/*
 * #%L
 * connected
 * %%
 * Copyright (C) 2017 Vertispan
 * %%
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
 * #L%
 */

import elemental2.dom.Event;
import elemental2.dom.MouseEvent;
import org.gwtproject.event.shared.HandlerRegistration;

/**
 * Mouse tracking through preview events, with a callback interface to implement behavior for a given drag
 */
public class DragTracker {

    /**
     *
     */
    interface DragHandling {
        default void click(MouseEvent event) {}
        default void startDrag(MouseEvent event) {}
        default void moveDrag(MouseEvent event) {}
        default void endDrag(MouseEvent event) {}
        default void cancelDrag() {}
    }

    private HandlerRegistration mouseEventPreview;
    private boolean moved;
    private boolean dragging = false;

    private DragHandling handler;

    public void start(elemental2.dom.Event event, DragHandling handler) {
        this.handler = handler;
        assert !dragging;
        dragging = true;
        moved = false;

        handler.startDrag((MouseEvent) event);//ok, this seems a bit silly, since we are calling it, not vice versa...
        //TODO switch to User, don't yet know the new metaphor for this...
        mouseEventPreview = com.vertispan.draw.connected.client.blank.Event.addNativePreviewHandler(captured -> {
            Event nativeEvent = captured.getNativeEvent();
            switch (nativeEvent.type) {
                case "mousemove":
                    move((MouseEvent) nativeEvent);
                    break;

                case "mouseup":
                    //click, or release drag
                    if (moved) {
                        endDrag((MouseEvent) nativeEvent);
                    } else {
                        endClick((MouseEvent) nativeEvent);
                    }
                    break;
            }
        });

        event.preventDefault();
    }

    private void endClick(MouseEvent nativeEvent) {
        //technically not dragging. probably should be called tracking instead.
        assert dragging;
        assert !moved;

        if (handler != null) {
            handler.click(nativeEvent);
        }

        dragging = false;
        mouseEventPreview.removeHandler();
    }

    private void endDrag(MouseEvent nativeEvent) {
        assert dragging;
        assert moved;

        if (handler != null) {
            handler.endDrag(nativeEvent);
        }

        dragging = false;
        mouseEventPreview.removeHandler();
    }

    private void move(MouseEvent nativeEvent) {
        assert dragging;
        moved = true;

        if (handler != null) {
            handler.moveDrag(nativeEvent);
        }
    }

    public void cancel() {
        dragging = false;
        mouseEventPreview.removeHandler();

        if (handler != null) {
            handler.cancelDrag();
        }
    }
}
