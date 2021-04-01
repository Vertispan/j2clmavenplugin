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