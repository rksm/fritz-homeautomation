.PHONY: nrepl chrome clean run-jar cljs cljs-prod http-server

# -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
# repl / dev

nrepl:
	clojure -R:dev:nrepl:test -C:nrepl:test -m fritz-homeautomation.nrepl

# -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

clean:
	rm -rf target .cpcache bin

# -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

CLJ_FILES := $(shell find . -type f \
		\( -path "./test/*" -o -path "./dev/*" -o -path "./src/*" \) \
		\( -iname "*.clj" -o -iname "*.cljc" \) -print)

CLJS_FILES := $(shell find . -type f \
		\( -path "./test/*" -o -path "./dev/*" -o -path "./src/*" \) \
		\( -iname "*.cljs" -o -iname "*.cljc" \) -print)

# -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

JS_FILES := resources/public/cljs-out/dev/
JS_PROD_FILES := resources/public/cljs-out/prod/

$(JS_FILES): $(CLJS_FILES) deps.edn dev.cljs.edn
	clojure -A:cljs

$(JS_PROD_FILES): $(CLJS_FILES) deps.edn prod.cljs.edn
	clojure -R:cljs -A:cljs-prod

cljs: $(JS_FILES)

cljs-prod: $(JS_PROD_FILES)

# -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

pom.xml: deps.edn
	clojure -Spom

AOT := target/classes

$(AOT): $(CLJ_FILES) $(CLJS_FILES)
	mkdir -p $(AOT)
	clojure -A:aot

JAR := target/fritz-homeautomation.jar

$(JAR): cljs-prod $(AOT) pom.xml
	mkdir -p $(dir $(JAR))
	clojure -C:aot -A:depstar -m hf.depstar.uberjar $(JAR) -m fritz_homeautomation.main
	chmod a+x $(JAR)

jar: $(JAR)

run-jar: jar
	java -jar $(JAR)
