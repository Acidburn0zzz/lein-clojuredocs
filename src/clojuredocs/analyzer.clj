(ns clojuredocs.analyzer
  (:require [clojure.java.io :as io]
            [clojure.tools.namespace.file :as ns-file]
            [clojure.tools.namespace.find :as ns-find]
            [cheshire.core :as json])
  (:import (java.io FileOutputStream OutputStreamWriter)
           (java.util.zip GZIPOutputStream)))

;; Project metadata about the clojure/core project (since it has no
;; leiningen project)
(def clojure-project
  {:name "clojure"
   :group "org.clojure"
   :url "http://clojure.org"
   :description "Clojure core environment and runtime"
   :scm "http://github.com/clojure/clojure"
   :license {:name "Eclipse Public License"
             :url "http://www.eclipse.org/legal/epl-v10.html"}
   :version (clojure-version)})

;; Blacklist of clojure/core files that don't correctly parse, but
;; actually don't need to (since we use the currently running clojure
;; version to grab these)
(def blacklist
  #{"core_deftype.clj"
    "core_print.clj"
    "core_proxy.clj"
    "genclass.clj"
    "gvec.clj"
    "cl_format.clj"
    "column_writer.clj"
    "dispatch.clj"
    "pprint_base.clj"
    "pretty_writer.clj"
    "print_table.clj"
    "utilities.clj"
    "java.clj"})

(defn munge-doc
  "Take a map of a clojure symbol, and munge it into an indexable doc map.
  Removes fields that can't be indexed and munges fields into better strings."
  [doc]
  (-> doc
      (dissoc :protocol :inline :inline-arities)
      (update-in [:ns] str)
      (update-in [:name] str)
      (update-in [:tag] #(when % (pr-str %)))
      (update-in [:arglists] (fn [arglists] (map str arglists)))))

(defn get-project-meta
  "Return a map of information about the project that should be indexed."
  [project]
  (select-keys project [:name :url :description :version :group :scm :license]))

(defn serialize-project-info
  "Writes json-encoded project information to a gzipped file. Filename format
  will be <project-name>-<version>.json.gz"
  [info]
  (let [filename (str (:name info) "-" (:version info) ".json.gz")]
    (println "[-] Writing output to" filename)
    (with-open [fos (FileOutputStream. filename)
                gzs (GZIPOutputStream. fos)
                os (OutputStreamWriter. gzs)]
      (.write os (json/encode info)))))

(defn read-namespace
  "Reads a file, returning a map of the namespace to a vector of maps with
  information about each var in the namespace."
  [f]
  (try
    (let [ns-dec (ns-file/read-file-ns-decl f)
          ns-name (second ns-dec)]
      (printf "[+] Processing %s...\n" (or ns-name f))
      (flush)
      (require ns-name)
      {(str ns-name) (->> ns-name
                          ns-interns
                          vals
                          (map meta)
                          (map munge-doc))})
    (catch Exception e
      (printf "Unable to parse (%s): %s\n" f e)
      {})))

(defn generate-all-data
  "Given a project map and list of source files, return a map of metadata about
  all vars in the files."
  [project source-files]
  (let [proj-meta (get-project-meta project)
        data-map (merge proj-meta
                        {:namespaces
                         (apply merge (for [source-file source-files]
                                        (read-namespace source-file)))})]
    data-map))

(defn gen-clojure
  "Given a string for the clojure source directory, generate gzipped json for
  the clojure/core project. Does some amount of finagling to ensure clojure/core
  can be read correctly."
  [clojure-dir]
  (let [clj-src-dir (io/file clojure-dir "src" "clj")
        clj-files (ns-find/find-clojure-sources-in-dir clj-src-dir)
        clj-files (remove #(contains? blacklist (.getName %)) clj-files)
        data-map (generate-all-data clojure-project clj-files)]
    (serialize-project-info data-map)
    (println "[=] Done.")))

(defn gen-project-docs
  "Given a leiningen project, generate json for all vars in that project."
  [project]
  (let [paths (or (:source-paths project) [(:source-path project)])
        source-files (mapcat #(-> %
                                  io/file
                                  ns-find/find-clojure-sources-in-dir)
                             paths)
        data-map (generate-all-data project source-files)]
    (serialize-project-info data-map)
    (println "[=] Done.")))

;; How to use this to generate clojure/doc data:
#_
(gen-clojure "/Users/hinmanm/src/clojure")
;; will generate 'clojure-1.4.0.json.gz' in the root directory
