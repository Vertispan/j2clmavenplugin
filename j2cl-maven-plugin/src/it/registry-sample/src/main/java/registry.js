goog.provide('serviceloader')

var map = {};
function register(key, creator) {
    map[key + '$j2cl$service$loader$key'] = creator;
}
function lookup(key) {
    return map[key + '$j2cl$service$loader$key']();
}

// Specify a default, and allow it to be overridden at build time
/** @define {string} */
const exampleDefault = goog.define('exampleDefault', 'python');
function lookupDefault() {
    return map[exampleDefault + '$j2cl$service$loader$key']();
}