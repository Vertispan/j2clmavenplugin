goog.provide('serviceloader')

var map = {};
function register(key, creator) {
    map[key] = creator;
}
function lookup(key) {
    return map[key]();
}

// Specify a default, and allow it to be overridden at build time
/** @define {string} */
const exampleDefault = goog.define('exampleDefault', 'python$j2cl$service$loader$key');
function lookupDefault() {
    return lookup(exampleDefault);
}