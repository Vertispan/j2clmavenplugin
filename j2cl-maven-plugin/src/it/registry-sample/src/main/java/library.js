goog.provide('hello')
goog.require('language')
goog.require('serviceloader')

function sayHello() {
    var defaultLanguage = lookupDefault();
    return "Hello, " + defaultLanguage.getNameStr() + "!";
}
