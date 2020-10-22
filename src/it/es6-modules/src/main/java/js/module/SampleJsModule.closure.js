// Declare a "full" closure module, so j2cl can reference it with requireType+module.get
goog.module('js.module.SampleJsModule');

// Depend on the "hybrid" module
const shim = goog.require('js.module.SampleJsModule.shim');

// when debugging, this can be helpful to see if we are correctly referencing the function
// console.log(shim.module.helloWorld() + " from closure.js");

// Re-export the module that was imported from the shim
exports = shim.module;