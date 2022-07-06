UnescapedAndEscapedCheck.prototype.m_test__java_lang_String = function(_arg) {
    /** @desc test */
    var MSG_test = goog.getMsg('@TranslationKey {$arg} !', { arg: _arg });
    return MSG_test;
}

UnescapedAndEscapedCheck.prototype.m_test2__java_lang_String = function(_arg) {
    /** @desc test2 */
    var MSG_test2 = goog.getMsg('{$arg}', { arg: _arg });
    return MSG_test2;
}

UnescapedAndEscapedCheck.prototype.m_test7__java_lang_String__java_lang_String = function(_arg,_arg1) {
    /** @desc test7 */
    var MSG_test7 = goog.getMsg('{$arg}&amp;{$arg1}', { arg1: _arg1, arg: _arg }, { unescapeHtmlEntities: true });
    return MSG_test7;
}
UnescapedAndEscapedCheck.prototype.m_test11__java_lang_String = function(_arg) {
    /** @desc test11 */
    var MSG_test11 = goog.getMsg('<div id="WOW">3{$arg}-!!!!!!!!!!!!!!!!!</div>', { arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test11;
}
UnescapedAndEscapedCheck.prototype.m_test6__java_lang_String__java_lang_String = function(_arg,_arg1) {
    /** @desc test6 */
    var MSG_test6 = goog.getMsg('<br/>{$arg}<div id="this">TranslationKey{$arg1}</div>', { arg1: _arg1,arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test6;
}
UnescapedAndEscapedCheck.prototype.m_test9__java_lang_String__java_lang_String__java_lang_String = function(_arg,_arg1,_arg2) {
    /** @desc test9 */
    var MSG_test9 = goog.getMsg('<div id="this">!@#$%^*(((</div>{$arg}&amp;{$arg1}@#$%^&amp;*(<div>{$arg2}</div>', { arg2: _arg2,arg1: _arg1,arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test9;
}
UnescapedAndEscapedCheck.prototype.m_test5__java_lang_String__java_lang_String__java_lang_String = function(_arg,_arg1,_arg2) {
    /** @desc test5 */
    var MSG_test5 = goog.getMsg('{$arg}TranslationKey{$arg1}TranslationKey{$arg2}', { arg2: _arg2,arg1: _arg1,arg: _arg });
    return MSG_test5;
}
UnescapedAndEscapedCheck.prototype.m_test3__java_lang_String__java_lang_String = function(_arg,_arg1) {
    /** @desc test3 */
    var MSG_test3 = goog.getMsg('{$arg}{$arg1}', { arg1: _arg1,arg: _arg });
    return MSG_test3;
}
UnescapedAndEscapedCheck.prototype.m_test8__java_lang_String__java_lang_String = function(_arg,_arg1) {
    /** @desc test8 */
    var MSG_test8 = goog.getMsg('<div id="this">!@#$%^*(((</div>{$arg}&amp;{$arg1}', { arg1: _arg1,arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test8;
}
UnescapedAndEscapedCheck.prototype.m_test16__java_lang_String = function(_arg) {
    /** @desc test16 */
    var MSG_test16 = goog.getMsg('&amp;&lt;div id="{$arg}"&gt;@TranslationKey&lt;/div&gt;', { arg: _arg }, { unescapeHtmlEntities: true } );
    return MSG_test16;
}
UnescapedAndEscapedCheck.prototype.m_test15__ = function() {
    /** @desc test15 */
    var MSG_test15 = goog.getMsg('7128890306670950348232162507662243061846645994402114603180927379544880168678174568758504050459062584');
    return MSG_test15;
}
UnescapedAndEscapedCheck.prototype.m_test4__java_lang_String__java_lang_String__java_lang_String__java_lang_String__java_lang_String = function(_arg,_arg1,_arg2,_arg3,_arg4) {
    /** @desc test4 */
    var MSG_test4 = goog.getMsg('Tests run: {$arg}, Failures: {$arg1}, Errors: {$arg2}, Skipped: {$arg3}, Time elapsed: {$arg4} sec', { arg3: _arg3,arg2: _arg2,arg4: _arg4,arg1: _arg1,arg: _arg });
    return MSG_test4;
}
UnescapedAndEscapedCheck.prototype.m_test10__java_lang_String = function(_arg) {
    /** @desc test10 */
    var MSG_test10 = goog.getMsg('<div id="someId">some content<br /><a href="#someRef">{$arg}</a>,</div>', { arg: _arg },{ unescapeHtmlEntities: true });
    return MSG_test10;
}
UnescapedAndEscapedCheck.prototype.m_test14__java_lang_String = function(_arg) {
    /** @desc test14 */
    var MSG_test14 = goog.getMsg('<div id="div1"><ph name="arg" >QWERTY</ph><div id="div2"><div id="div3">RRRRRRR{$arg}></div><ph name="arg" /><div id="div4"></div></div></div><ph name="arg" >QWERTY</ph>', { arg: _arg }, { unescapeHtmlEntities: true });
    return MSG_test14;
}
UnescapedAndEscapedCheck.prototype.m_test12__ = function() {
    /** @desc test12 */
    var MSG_test12 = goog.getMsg('<div id="div1"><div id="div2"><div id="div3"></div><div id="div4"></div></div></div>',{},{ unescapeHtmlEntities: true });
    return MSG_test12;
}
UnescapedAndEscapedCheck.prototype.m_test13__ = function() {
    /** @desc test13 */
    var MSG_test13 = goog.getMsg('<div>TranslationKey</div>', {}, { unescapeHtmlEntities: true });
    return MSG_test13;
}

UnescapedAndEscapedCheck.prototype.m_test17__java_lang_String__java_lang_String = function(_var1,_var2) {
    /** @desc test17 */
    var MSG_test17 = goog.getMsg('<br/>{$var1}<div id="this">inner text {$var2}</div>', { var2: _var2,var1: _var1 });
    return MSG_test17;
}
UnescapedAndEscapedCheck.prototype.m_test18__java_lang_String__java_lang_String = function(_arg,_arg1) {
    /** @desc test18 */
    var MSG_test18 = goog.getMsg('<div id="_this">!@#$%^*(((</div>{$arg}&amp;{$arg1}', { arg1: _arg1,arg: _arg });
    return MSG_test18;
}
UnescapedAndEscapedCheck.prototype.m_test19__java_lang_String__java_lang_String__java_lang_String = function(_arg,_arg1,_arg2) {
    /** @desc test19 */
    var MSG_test19 = goog.getMsg('<div id="_this">!@#$%^*(((</div>{$arg}&amp;{$arg1}@#$%^&amp;*(<div>{$arg2}</div>', { arg2: _arg2,arg1: _arg1,arg: _arg });
    return MSG_test19;
}

UnescapedAndEscapedCheck.prototype.m_test20__java_lang_String = function(_arg) {
    /** @desc test20 */
    var MSG_test20 = goog.getMsg('<div id="_someId">some content<br /><a href="#someRef">{$arg}</a>,</div>', { arg: _arg });
    return MSG_test20;
}
UnescapedAndEscapedCheck.prototype.m_test21__java_lang_String = function(_arg) {
    /** @desc test21 */
    var MSG_test21 = goog.getMsg('<div id="div_id">3{$arg}-!!!!!!!!!!!!!!!!!</div>', { arg: _arg });
    return MSG_test21;
}
UnescapedAndEscapedCheck.prototype.m_test22__ = function() {
    /** @desc test22 */
    var MSG_test22 = goog.getMsg('<div id="div21"><div id="div22"><div id="div23"></div><div id="div14"></div></div></div>');
    return MSG_test22;
}
UnescapedAndEscapedCheck.prototype.m_test23__ = function() {
    /** @desc test23 */
    var MSG_test23 = goog.getMsg('<div>inner content</div>');
    return MSG_test23;
}
UnescapedAndEscapedCheck.prototype.m_test24__java_lang_String = function(_arg) {
    /** @desc test24 */
    var MSG_test24 = goog.getMsg('<div id="div21"><ph name="arg" >QWERTY</ph><div id="div22"><div id="div23">RRRRRRR{$arg}></div><ph name="arg" /><div id="div4"></div></div></div><ph name="arg" >QWERTY</ph>', { arg: _arg });
    return MSG_test24;
}
