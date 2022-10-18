UnescapedAndEscapedCheck.prototype.test = function(_arg) {
    /** @desc test */
    var MSG_test = goog.getMsg('@TranslationKey {$arg} !', { arg: _arg });
    return MSG_test;
}

UnescapedAndEscapedCheck.prototype.test2 = function(_arg) {
    /** @desc test2 */
    var MSG_test2 = goog.getMsg('{$arg}', { arg: _arg });
    return MSG_test2;
}

UnescapedAndEscapedCheck.prototype.test7 = function(_arg,_arg1) {
    /** @desc test7 */
    var MSG_test7 = goog.getMsg('{$arg}&amp;{$arg_1}', { arg_1: _arg1, arg: _arg }, { unescapeHtmlEntities: true });
    return MSG_test7;
}
UnescapedAndEscapedCheck.prototype.test11 = function(_arg) {
    /** @desc test11 */
    var MSG_test11 = goog.getMsg('<div id="WOW">3{$arg}-!!!!!!!!!!!!!!!!!</div>', { arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test11;
}
UnescapedAndEscapedCheck.prototype.test6 = function(_arg,_arg1) {
    /** @desc test6 */
    var MSG_test6 = goog.getMsg('<br/>{$arg}<div id="this">TranslationKey{$arg_1}</div>', { arg_1: _arg1,arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test6;
}
UnescapedAndEscapedCheck.prototype.test9 = function(_arg,_arg1,_arg2) {
    /** @desc test9 */
    var MSG_test9 = goog.getMsg('<div id="this">!@#$%^*(((</div>{$arg}&amp;{$arg_1}@#$%^&amp;*(<div>{$arg_2}</div>', { arg_2: _arg2,arg_1: _arg1,arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test9;
}
UnescapedAndEscapedCheck.prototype.test5 = function(_arg,_arg1,_arg2) {
    /** @desc test5 */
    var MSG_test5 = goog.getMsg('{$arg}TranslationKey{$arg_1}TranslationKey{$arg_2}', { arg_2: _arg2,arg_1: _arg1,arg: _arg });
    return MSG_test5;
}
UnescapedAndEscapedCheck.prototype.test3 = function(_arg,_arg1) {
    /** @desc test3 */
    var MSG_test3 = goog.getMsg('{$arg}{$arg_1}', { arg_1: _arg1,arg: _arg });
    return MSG_test3;
}
UnescapedAndEscapedCheck.prototype.test8 = function(_arg,_arg1) {
    /** @desc test8 */
    var MSG_test8 = goog.getMsg('<div id="this">!@#$%^*(((</div>{$arg}&amp;{$arg_1}', { arg_1: _arg1,arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test8;
}
UnescapedAndEscapedCheck.prototype.test16 = function(_arg) {
    /** @desc test16 */
    var MSG_test16 = goog.getMsg('&amp;&lt;div id="{$arg}"&gt;@TranslationKey&lt;/div&gt;', { arg: _arg }, { unescapeHtmlEntities: true } );
    return MSG_test16;
}
UnescapedAndEscapedCheck.prototype.test15 = function() {
    /** @desc test15 */
    var MSG_test15 = goog.getMsg('7128890306670950348232162507662243061846645994402114603180927379544880168678174568758504050459062584');
    return MSG_test15;
}
UnescapedAndEscapedCheck.prototype.test4 = function(_arg,_arg1,_arg2,_arg3,_arg4) {
    /** @desc test4 */
    var MSG_test4 = goog.getMsg('Tests run: {$arg}, Failures: {$arg_1}, Errors: {$arg_2}, Skipped: {$arg_3}, Time elapsed: {$arg_4} sec', { arg_3: _arg3,arg_2: _arg2,arg_4: _arg4,arg_1: _arg1,arg: _arg });
    return MSG_test4;
}
UnescapedAndEscapedCheck.prototype.test10 = function(_arg) {
    /** @desc test10 */
    var MSG_test10 = goog.getMsg('<div id="someId">some content<br /><a href="#someRef">{$arg}</a>,</div>', { arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test10;
}
UnescapedAndEscapedCheck.prototype.test14 = function(_arg) {
    /** @desc test14 */
    var MSG_test14 = goog.getMsg('<div id="div1"><div id="div2"><div id="div3">RRRRRRR{$arg}</div><div id="div4"></div></div></div>', { arg: _arg }, { unescapeHtmlEntities: true });
    return MSG_test14;
}
UnescapedAndEscapedCheck.prototype.test12 = function() {
    /** @desc test12 */
    var MSG_test12 = goog.getMsg('<div id="div1"><div id="div2"><div id="div3"></div><div id="div4"></div></div></div>',{},{ unescapeHtmlEntities: true });
    return MSG_test12;
}
UnescapedAndEscapedCheck.prototype.test13 = function() {
    /** @desc test13 */
    var MSG_test13 = goog.getMsg('<div>TranslationKey</div>', {}, { unescapeHtmlEntities: true });
    return MSG_test13;
}

UnescapedAndEscapedCheck.prototype.test17 = function(_var1,_var2) {
    /** @desc test17 */
    var MSG_test17 = goog.getMsg('<br/>{$var_1}<div id="this">inner text {$var_2}</div>', { var_2: _var2,var_1: _var1 });
    return MSG_test17;
}
UnescapedAndEscapedCheck.prototype.test18 = function(_arg,_arg1) {
    /** @desc test18 */
    var MSG_test18 = goog.getMsg('<div id="_this">!@#$%^*(((</div>{$arg}&amp;{$arg_1}', { arg_1: _arg1,arg: _arg });
    return MSG_test18;
}
UnescapedAndEscapedCheck.prototype.test19 = function(_arg,_arg1,_arg2) {
    /** @desc test19 */
    var MSG_test19 = goog.getMsg('<div id="_this">!@#$%^*(((</div>{$arg}&amp;{$arg_1}@#$%^&amp;*(<div>{$arg_2}</div>', { arg_2: _arg2,arg_1: _arg1,arg: _arg });
    return MSG_test19;
}

UnescapedAndEscapedCheck.prototype.test20 = function(_arg) {
    /** @desc test20 */
    var MSG_test20 = goog.getMsg('<div id="_someId">some content<br /><a href="#someRef">{$arg}</a>,</div>', { arg: _arg });
    return MSG_test20;
}
UnescapedAndEscapedCheck.prototype.test21 = function(_arg) {
    /** @desc test21 */
    var MSG_test21 = goog.getMsg('<div id="div_id">3{$arg}-!!!!!!!!!!!!!!!!!</div>', { arg: _arg });
    return MSG_test21;
}
UnescapedAndEscapedCheck.prototype.test22 = function() {
    /** @desc test22 */
    var MSG_test22 = goog.getMsg('<div id="div21"><div id="div22"><div id="div23"></div><div id="div14"></div></div></div>');
    return MSG_test22;
}
UnescapedAndEscapedCheck.prototype.test23 = function() {
    /** @desc test23 */
    var MSG_test23 = goog.getMsg('<div>inner content</div>');
    return MSG_test23;
}
UnescapedAndEscapedCheck.prototype.test24 = function(_arg) {
    /** @desc test24 */
    var MSG_test24 = goog.getMsg('<div id="div21"><div id="div22"><div id="div23">RRRRRRR{$arg}></div><div id="div4"></div></div></div>', { arg: _arg });
    return MSG_test24;
}
