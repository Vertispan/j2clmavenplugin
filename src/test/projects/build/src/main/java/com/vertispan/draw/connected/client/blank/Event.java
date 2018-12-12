package com.vertispan.draw.connected.client.blank;

/*
 * #%L
 * Connected
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

import elemental2.dom.EventListener;
import org.gwtproject.event.shared.HandlerRegistration;

import java.util.ArrayList;
import java.util.List;

import static elemental2.dom.DomGlobal.window;

/**
 * Created by colin on 9/16/17.
 */
public class Event {
    private static final EventListener dispatchCapturedMouseEvent;
    private static final EventListener dispatchCapturedEvent;
    private static final List<NativePreviewHandler> previewHandlers = new ArrayList<>();
    static {
        dispatchCapturedEvent = Event::dispatchCapturedEvent;
        dispatchCapturedMouseEvent = Event::dispatchCapturedEvent;

        window.addEventListener("click", dispatchCapturedMouseEvent, true);
        window.addEventListener("dblclick", dispatchCapturedMouseEvent, true);
        window.addEventListener("mousedown", dispatchCapturedMouseEvent, true);
        window.addEventListener("mouseup", dispatchCapturedMouseEvent, true);
        window.addEventListener("mousemove", dispatchCapturedMouseEvent, true);
        window.addEventListener("mouseover", dispatchCapturedMouseEvent, true);
        window.addEventListener("mouseout", dispatchCapturedMouseEvent, true);
        window.addEventListener("mousewheel", dispatchCapturedMouseEvent, true);

        window.addEventListener("keydown", dispatchCapturedEvent, true);
        window.addEventListener("keyup", dispatchCapturedEvent, true);
        window.addEventListener("keypress", dispatchCapturedEvent, true);

        window.addEventListener("touchstart", dispatchCapturedMouseEvent, true);
        window.addEventListener("touchend", dispatchCapturedMouseEvent, true);
        window.addEventListener("touchmove", dispatchCapturedMouseEvent, true);
        window.addEventListener("touchcancel", dispatchCapturedMouseEvent, true);
        window.addEventListener("gesturestart", dispatchCapturedMouseEvent, true);
        window.addEventListener("gestureend", dispatchCapturedMouseEvent, true);
        window.addEventListener("gesturechange", dispatchCapturedMouseEvent, true);

    }
    public interface NativePreviewHandler {
        void onPreviewNativeEvent(Event.NativePreviewEvent var1);
    }

    public static class NativePreviewEvent {
        private static Event.NativePreviewEvent singleton = new NativePreviewEvent();
        private boolean isCanceled = false;
        private boolean isConsumed = false;
        private boolean isFirstHandler = false;
        private elemental2.dom.Event nativeEvent;

        private NativePreviewEvent() {
        }

        public elemental2.dom.Event getNativeEvent() {
            return nativeEvent;
        }
    }

    public static HandlerRegistration addNativePreviewHandler(NativePreviewHandler handler) {
        previewHandlers.add(handler);//TODO consider a nicer way to add this after events finish going off like HandlerManager does?

        return () -> previewHandlers.remove(handler);
    }

    private static void dispatchCapturedEvent(elemental2.dom.Event event) {
        //in theory we could branch here and support gwt-user's old mouse capture tools

        //all handlers get a crack at this, then we check if it was canceled
        NativePreviewEvent.singleton.nativeEvent = event;
        for (int i = 0; i < previewHandlers.size(); i++) {
            previewHandlers.get(i).onPreviewNativeEvent(NativePreviewEvent.singleton);
        }

        boolean ret = !NativePreviewEvent.singleton.isCanceled || NativePreviewEvent.singleton.isConsumed;
        if (!ret) {
            event.stopPropagation();
            event.preventDefault();
        }
    }
}
