/*
 * Copyright © 2020 j2cl-maven-plugin authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.helloworld;

import elemental2.dom.HTMLElement;
import elemental2.dom.HTMLTemplateElement;
import jsinterop.annotations.JsType;

import static elemental2.dom.DomGlobal.customElements;
import static elemental2.dom.DomGlobal.document;

@JsType
public class HelloWorld extends HTMLElement {
  private static final HTMLTemplateElement TEMPLATE =
      (HTMLTemplateElement) document.getElementById("hello-world");

  private static final AttachShadowOptionsType SHADOW_OPTIONS;
  static {
    SHADOW_OPTIONS = AttachShadowOptionsType.create();
    SHADOW_OPTIONS.setMode("open");
  }

  public HelloWorld() {
    attachShadow(SHADOW_OPTIONS)
        .appendChild(TEMPLATE.content.cloneNode(true));
  }

  public static void main() {
    customElements.define("hello-world", HelloWorld.class);
  }
}