echo "# Without any args, clawk is a pass-through, iterpreting the lines as edn."
echo "$ seq 1 4 | clawk"
seq 1 4 | clawk
echo

echo "# But a variety of input/output formats are supported (edn, json, csv, tsv, text)"
echo "$ echo -e \"a1,b1,c1\na2,b2,c2\" | clawk -i csv -o json"
echo -e "a1,b1,c1\na2,b2,c2" | clawk -i csv -o json
echo

echo "# A mapping fn (-m) can operate on and transform the value of a line bound to 'x'"
echo "$ echo -e \"abc\ndef\" | clawk -m '{:value (string/upper-case x)}'"
echo -e "abc\ndef" | clawk -m '{:value (string/upper-case x)}'
echo

echo "# A reduce fn (-r) can be used to count the lines (ignoring the value)."
echo "# The -y arg provides the initial value for the reduce step (interpreted as edn)."
echo "$ echo -e \"a\nb\nc\" | clawk -i csv -r '(+ xs 1)' -y 0"
echo -e "a\nb\nc" | clawk -i csv -r '(+ xs 1)' -y 0 -p
echo

echo "# A more advanced reduce can be used to aggregate flexibly"
echo "$ seq 1 9 | clawk -r '(update-in xs [(if (odd? x) :odd :even)] inc)' -y '{:odd 0 :even 0}'"
seq 1 9 | clawk -r '(update-in xs [(if (odd? x) :odd :even)] inc)' -y '{:odd 0 :even 0}'
echo

echo "# A simple auto-incrementing value through an atom."
echo "# The list of trailing args can be arbitary code or clj files (each of which is eval'd)."
echo "# External files can not require additional dependencies, but can leverage what clawk uses."
echo "$ echo -e \"a\nb\nc\" | clawk -y 0 -r '(+ xs x)' -m '(swap z inc)' '(def z (atom 0))'"
echo -e "a\nb\nc" | clawk -m '(swap! z inc)' '(def z (atom 0))'
echo
