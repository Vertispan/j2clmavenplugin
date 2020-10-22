// Depend on the original es6 module
import * as module from './SampleJsModule.js';

// Mark this as a "hybrid" es6/closure module
goog.declareModuleId('js.module.SampleJsModule.shim');

// when debugging, this can be helpful to see if we are correctly referencing the function
// console.log(module.helloWorld() + " from shim.js");

// Re-export the whole module
export {module};