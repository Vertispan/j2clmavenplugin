goog.provide('py')
goog.require('language')
goog.require('serviceloader')

/**
 *
 * @constructor
 * @implements ProgrammingLanguage
 */
function PythonLanguage() {

}
PythonLanguage.prototype.getNameStr = function() {
    return "Python";
};
register("python$j2cl$service$loader$key", () => new PythonLanguage());