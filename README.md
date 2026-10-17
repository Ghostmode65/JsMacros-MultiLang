# JsMacros-MultiLang
Lua, Python and Ruby all packed into one.

Jsmacros Compatibility 
Edition | Verisons
|-------|--------|
Legacy  | 1.8.9 - 1.21.1
Community | 1.21.5 - 26.x
Reloaded | 26.x

Language | Bridge Version | Languge Version 
|-----------|-----------|------------|
[LuaJ](https://github.com/luaj/luaj) | 3.2 | Lua 5.2 <br>
[Luava](https://github.com/KienPC1234/luava) | 0.2.5 | 5.4  <br>
Luava can be enabled in java versons 21+, as an experimental option over LuaJ .<br> | |
[JRuby](https://github.com/jruby/jruby) `Java 21+`| 10.1 | Ruby 4.0   <br>
JRuby `Java 17` | 9.4 | Ruby 3.1  <br> | |
[GraalPy](https://github.com/oracle/graalpython)| 24.0.1 | Python 3.10 <br>
GraalPy can be enabled for legacy, as an experimental over jython.<br> | |
[jython](https://github.com/jython/jython) | 2.7.2 | Python 2.7
jython can be enabled in Community and Reloaded, as an experimental over GraalPy| |



## Benmarks

[GraalJS | JavaScript] <br> 
- Prime (2000): max 430 ms; min 393 ms; average 416 ms
- getBlock (4 chunks): max 265 ms; min 171 ms; average 191 ms

[GraalPy | Python 3.10.13] (No Graalvm) <br>  
- Prime (2000): max 5579 ms; min 5348 ms; average 5496 ms
- getBlock (4 chunks): max 1130 ms; min 399 ms; average 547 ms

[GraalPy | Python 3.10.13] ([With Graalvm](https://github.com/graalvm/graalvm-ce-builds/releases/)) <br>
- Prime (2000): max 38.91 ms; min 30.55 ms; average 32.72 ms
- getBlock (4 chunks): max 78.47 ms; min 50.93 ms; average 61.48 ms

[Jyphon | Python 2.7] <br>
- Prime (2000): max 7552 ms; min 7064 ms; average 7280 ms
- getBlock (4 chunks): max 490 ms; min 413 ms; average 430 ms

[JRuby 10.1 | Ruby 4.0.0] <br>
- Prime (2000): max 2551 ms; min 2386 ms; average 2465 ms
- getBlock (4 chunks): max 196 ms; min 187 ms; average 193 ms

[LuaJ | Lua 5.2] <br>
- Prime (2000): max 1730 ms; min 1583 ms; average 1632 ms
- getBlock (4 chunks): max 223 ms; min 212 ms; average 216 ms

[LuaJ + Luava JIT] <br>
- Prime (2000): max 52.10 ms; min 51.50 ms; average 51.71 ms
- getBlock (4 chunks): max 235 ms; min 189 ms; average 211 ms

*Not an actual representation for work loads.  <br>

## Credits <br>
[WagYourTail](https://modrinth.com/mod/jsmacros-reloaded) original exentions<br>
[grepsedawk](https://modrinth.com/mod/jsmacros-reloaded) updated ruby<br>
