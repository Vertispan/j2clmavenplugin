goog.provide('java')
goog.require('language')
goog.require('serviceloader')

/**
 *
 * @constructor
 * @implements ProgrammingLanguage
 */
function JavaLanguage() {

}
JavaLanguage.prototype.getNameStr = function() {
    return "Java";
};
register("java$j2cl$service$loader$key", () => new JavaLanguage());
