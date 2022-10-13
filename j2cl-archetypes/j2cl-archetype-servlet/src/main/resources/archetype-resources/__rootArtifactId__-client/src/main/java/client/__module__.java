package ${package}.client;

import ${package}.shared.SharedType;

import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLButtonElement;
import elemental2.dom.HTMLDivElement;
import elemental2.dom.Response;
import elemental2.promise.Promise;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

@JsType
public class ${module} {

    public void onModuleLoad() {
        HTMLDivElement wrapper = (HTMLDivElement) DomGlobal.document.createElement("div");
        wrapper.classList.add("wrapper");

        HTMLButtonElement btn = (HTMLButtonElement) DomGlobal.document.createElement("button");
        btn.classList.add("myButton");
        btn.textContent = SharedType.sayHello("HTML");

        btn.addEventListener("click", evt -> {
            goGetData(btn);
        });

        wrapper.appendChild(btn);

        DomGlobal.document.body.appendChild(wrapper);
    }

    private void goGetData(HTMLButtonElement button) {
        DomGlobal.fetch("/hello.json?name=J2cl")
            .then(Response::json)
            .then(json -> {
                String string = Js.asPropertyMap(json).getAsAny("response").asString();
                button.textContent = string;
                return Promise.resolve(json);
            });
    }
}
