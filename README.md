NOTE: This fork largely deviates from the original.

# clawk

Kinda like awk, but Clojure. Reads each line of STDIN and processes it using user-provided code.

Pros:

* Full power for clojure at your disposal without learning awk.
* Doing the `reader/line-seq/doseq/split/print` dance in Clojure is tedious.
* If you happen to have files where each record is in EDN, awk and friends don't help.

Cons:

* Awk and friends have many man years put into them. Clawk has a couple man hours.
* JVM Startup time. When the file's big enough or a stream being tailed, this doesn't bug me much.
* Speed. For a task that awk can do, I can't imagine it not being way, way, way, way faster than this.

I basically see this as part of a larger pipeline, for when you can do something easier with clawk than with `sed`, `grep`, `cut`, `tr`, `wc`, and all the others, especially when you need to manipulate edn/json/etc.

## Usage

`$ clawk [options] [expressions-and-or-files]`

### Options
`
 Switches                           Default  Desc
 --------                           -------  ----
 -h, --no-help, --help              false    Print this message
 -c, --no-concat, --concat          false    Apply concat to the result of the mapper (mapcat).
 -g, --no-debug, --debug            false    Debug by printing stacktraces from exceptions.
 -d, --delimiter                             Delimiter used to split each line (text only). A string or #"regex"
 -i, --format-in                    edn      The input data format (edn, csv, tsv, json, text)
 -o, --format-out                   edn      The output data format (edn, csv, tsv, json, text)
 -n, --no-new-lines, --new-lines    true     Whether to emit new-lines after each line of output.
 -p, --no-parallel, --parallel      false    Parallelized processing (pmap instead of map).
 -t, --no-trim, --trim              true     Trim each line before decoding.
 -k, --no-keep-blank, --keep-blank  false    Keep blank lines
 -f, --filter                                Filter fn (eval'd). It is supplied a var 'x'; example: '(not (empty? x))'
 -m, --mapper                                Mapper fn (eval'd). It is supplied a var 'x'; example: '(inc x)'
 -r, --reducer                               Reducer fn (eval'd). It is supplied vars 'xs' and 'x'; example: '(+ xs x)'
 -e, --map-exception                         Exception handler for the map fn. Given args [e x]
 -u, --reduce-exception                      Exception handler for the reduce fn. Given args [e xs x]
 -y, --identity                     []       Used as the initializer value for the -r opt (only); example for a seq of numbers: 0.

The default value applies to the positive version of the switch, rather than the "no-" version.  For example, parallel is disabled by default.
`

### Expressions and/or files

An optional mixed list of additional expessions and files can be evaled in to clawk *before* it processes the STDIN, allowing you to reuse your functions and code moslty in an editor, rather than in the terminal (highly recommended).

`$ clawk '(def counter (atom 0))'

This initializes an atom that is now available in the filter/map/reduce steps.

`$ clawk ~/.clawk/http.clj`

This loads some helper utilities for interacting with http.

Files that are loaded should be prep'd for eval (no additional ns/require/import).

You have the following available (subject to change):

```
(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clj-http.client :as http]
         '[clojure.string :as string]
         '[clojure.xml :as xml]
         '[clojure.zip :as zip])
```

## Examples

Each line of stdin is interpreted by default as "edn" (not as "text"). There are some other default options enabled, but mostly, clawk will function as a pass-through, by using identity functions instead of your custom code.

```
$ seq 1 3 | clawk
1
2
3
```

So, in affect, what is being ran if no args are specified is:

```
$ seq 1 3 | clawk -m '(identity x)'
```

*of course you could just use `x` and omit `identity`*

### Data Formats

As was said, each line of STDIN is interpreted as "edn" by default, but "json", "csv", "tsv", and "text" are available.  You can choose both an input (-i) and an output (-o).  The output is also defaulted to "edn", but can be overridden.

```
$ echo -e '1\n2\n3\n' | clawk -i 'edn' -o 'edn' -m '(* x x)'
1
4
9
```

This is pretty nice when you have a Clojure map on each line. `x` becomes your map and is easy to work with.  Notice that if nil is returned from the mapper (the :404 map), then it is considered blankd and is filtered out by default.

```
$ echo -e '{:name "joe"}\n{:404 "skip me"}\n{:name "alex"}' | clawk -m '(:name x)'
"joe"
"alex"
```

But is also trivial to take in edn and output to json.

```
$ echo -e '{:name "joe"}\n{:name "alex"}' | clawk -o json
{"name":"joe"}
{"name":"alex"}
```

Or take in a csv and convert it do edn:

```
$ echo -e '1,2,3\na,b,c\n3,4,5\nd,e,f' | clawk -i 'csv' -o 'edn'
["1" "2" "3"]
["a" "b" "c"]
["3" "4" "5"]
["d" "e" "f"]
```

### Mapping
Your mapper fn is just like a clojure map.  The only thing special about it is a special var `x` to represent the argument.

```
echo -e "abc\ndef" | clawk -m '{:lower x :upper (string/upper-case x)}'
{:lower "abc" :upper "ABC"}
{:lower "def" :upper "DEF"}
```

### Filtering
If your mapping fn returns nil then no output is written, but you can also use a filter fn to return falsey and omit a line.  The filter fn is called before the mapper fn.

```
$ echo -e "1\n2\n3\n4\n5\n6\n" | clawk -f '(< 4 (* x x) 30)'
3
4
5
```

*and, of course, this is no replacement for `grep` for filtering on regex*

### Reducer
You can take control of the result and do aggregation or grouping or any last minute transforms in the reducer phase, which happens last.

Since a reduce fn takes two args, you'll have two special vars: `xs` and `x`, representing the accumulator and the current value, respectively.

Here is a simple line count that ignores the contents of the line (note, use `wc -l` for this if applicable).

```
$ echo -e "a\nb\nc" | clawk -r '(+ xs 1)' -y 0
3
```
```
$ seq 1 4 | clawk -r '(+ xs x)' -y 0 -m '(* x x)'
30
```

### Delimiter

If you use the "text" input format, you may specify a delimiter with `-d`, then each line is split and `x` is bound to the resulting vector:

```
$ echo -e "1,2,3\n4,5,6\n7,8,9\n" | clawk -i text -d ',' -o text -m '(x 1)'
2
5
8
```

*of course you'd just use `cut` for this*

But wait, why were there quotes around the numbers?  Because you asked the input to be interpreted as a text string and the default edn output is faithfully representing it by prn'ing it.

The value passed to `-d` can also be a regex:

```
$ echo -e "foo234bar456yum\nbaz9gar\n" | clawk -i text -d '#"\d+"' -o text -m '(format "%s-%s" (x 1) (x 0))'
bar-foo
gar-baz
```

## To Build

```
$ ./make-sh.sh
```

Now move `target/clawk` to somewhere on your PATH.

*Note that this is an ugly hack and may not actually work on many systems. In that case, it's back to just using the uberjar or however you like to run Clojure apps*

## License

Copyright © 2013 Dave Ray

Distributed under the Eclipse Public License, the same as Clojure.
