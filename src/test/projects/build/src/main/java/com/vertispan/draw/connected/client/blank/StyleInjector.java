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

import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLStyleElement;

import static elemental2.dom.DomGlobal.document;

/**
 * Created by colin on 9/16/17.
 */
public class StyleInjector {
    public static void inject(String cssContent) {
        HTMLStyleElement style = (HTMLStyleElement) document.createElement("style");
        style.appendChild(document.createTextNode(cssContent));
        document.head.appendChild(style);
    }
}
