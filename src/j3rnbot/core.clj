(ns j3rnbot.core
  (:use
    [irclj.core]
    [clojure.pprint]
    [korma.db]
    [korma.core :exclude (join)])
  (:require
    [clojure.data.json :as json]
    [clojure.string :as string]
    [clojure.core.match :refer (match)]))

; Load settings
(def settings
  (json/read-str
    (slurp "settings.json")))

; Constants
(def host     (get settings "server"))
(def port     (get settings "port"))
(def nick     (get settings "bot_nick"))
(def nick_length (count nick))
(def pass     (get settings "bot_pass"))
(def master   (get settings "master"))
(def channels (get settings "channels"))

; Database settings
(def db-name  (get settings "db-name"))
(def db-user  (get settings "db-user"))
(def db-pass  (get settings "db-pass"))

; Setup database
(defdb db (postgres {:db db-name
                     :user db-user
                     :password db-pass}))

(declare users votes)

(defentity users
  (has-many votes))
(defentity votes
  (belongs-to users))

; Compose a string summarizing the votes
(defn vote-string [irc args]
  (reply irc args
         (or
           (not-empty
             (reduce-kv
               #(str %1 %2 ": " (count %3) " ")
               ""
               (group-by :item (select votes
                                       (where {:old false})))))
           "No votes")))

; Compose a string summarizing who voted for what
(defn rapsheet [irc args]
  (reply irc args
    (or
      (not-empty
        (reduce-kv
          #(str %1 "* " %2 ": " (apply str
                                      (string/join ", "
                                                  (map :nick %3))) " *")
        ""
        (group-by :item (select votes
                                (with users)
                                (where {:old false})))))
    "No votes")))

; Count all of the votes
(defn count-votes [irc args]
  (reply irc args
         (str
           ; Get count of votes from DB. If nil (0 votes in DB), return 0
           (or
             (get
               (first
                 (select votes
                         (where {:old false})
                         (aggregate (count :*) :count)))
               :count)
             0)
           " votes")))

(defn whodunnit [irc args item]
  (reply irc args
         (str
           (or
             (not-empty
               (string/join
                 ", "
                 (map
                   :nick
                   (select votes
                           (with users)
                           (where {:item item
                                   :old false})))) )
             "Nobody")
           " voted for that item")))

(defn whos-voted [irc args]
  (reply irc args
         (or
           (not-empty
             (string/join
               ", "
               (map
                 #(:nick %)
                 (select votes
                         (with users)
                         (where {:old false})))))
           "Nobody")))

(defn dunnitwho [irc args nick]
  (reply irc args
         (or
           (not-empty
             (let [user_id (:id (first (select users (where {:nick nick}))))]
               (if user_id
                 (:item
                   (first
                     (select votes
                             (where {:old false
                                     :users_id user_id})
                             (fields :item))))
                 (str "User with nick \"" nick "\" not found"))))
           (str nick " has not voted"))))

; Clear all votes from the state
(defn clear-votes! [irc args]
  (do
    (update votes
            (set-fields {:old true})
            (where {:old false}))
    (reply irc args "Votes cleared")))

; Remove a single vote for the given item
; If the new count is 0, remove it from the count
(defn rm-vote! [irc args nick]
  (reply irc args
         (let [user (first (select users (where {:nick nick})))]
           (if user
             (str
               "Vote for "
               (:item
                 (delete votes
                         (where {:users_id (:id user)
                                 :old false})))
               " deleted")
             "You are not whitelisted!"))))

; If it is not voted for, vote for it
; If it has been voted for, increment the votes
(defn vote! [irc args nick item]
  (reply irc args
         (let [user (first
                      (select users
                              (where {:nick nick})
                              (limit 1)))]
           (if user
             (do
               ; If previous vote, delete
               (if (not-empty (select votes
                                      (where {:users_id (:id user)
                                              :old false})))
                 (rm-vote! irc args nick))

               ; Ensure vote is not too long
               (if (<= (count item) 30)
                 (do
                   (insert votes
                           (values {:users_id (:id user)
                                    :item item}))
                   (vote-string irc args))
                 "That item's name is too long"))
             "You are not whitelisted, sorry"))))


(defn whitelist! [irc args nick]
  (reply irc args
         (if (not (first (select users (where {:nick nick}))))
           (if (<= (count nick) 30)
             (do
               (insert users (values {:nick nick}))
               (str nick " is now whitelisted"))
             "That nick is wayyyy too long")
           (str nick " is already whitelisted"))))


;;; Process revelant commands

; Respond to user's request
(defn obey-user [irc args tokens sender]
  (case (first tokens)
    ".votes"
    (vote-string irc args)
    ".vote"
    (if (not (nil? (get tokens 1)))
      (vote! irc args sender (get tokens 1)))
    ".rmvote"
    (rm-vote! irc args sender)
    ".count"
    (count-votes irc args)
    ".rapsheet"
    (rapsheet irc args)
    ".whodunnit"
    (whodunnit irc args (get tokens 1))
    ".whosvoted"
    (whos-voted irc args)
    ".whathaveyoudone"
    (dunnitwho irc args (get tokens 1))
    ()))

; Respond to master's request
(defn obey-master [irc args tokens]
  (case (first tokens)
    ".join"
    (let [channel (get tokens 1)]
      (join irc channel)
      (reply irc args (str "Joined " channel)))
    ".part"
    (let [channel (get tokens 1)]
      (reply irc args ("Bye!"))
      (part irc channel))
    ".whitelist"
    (let [nick (get tokens 1)]
      (whitelist! irc args nick))
    ".vote-as"
    (let [nick (get tokens 1)
          vote (get tokens 2)]
      (vote! irc args nick vote))
    ".rm-vote-as"
    (let [nick (get tokens 1)]
      (rm-vote! irc args nick))
    ".clear" (clear-votes! irc args)
    ".die"
    (do
      (reply irc args "Goodbye, cruel world!")
      (System/exit 0))
    (obey-user irc args tokens (string/lower-case master))))

; Messages directly to me
(defn respond [irc args tokens]
  (case (first tokens)
    ("hello" "hello!" "hi" "hi!")
    (reply irc args (str "Hello, " (:nick args)))
    ("beep" "boop")
    (reply irc args "boop")
    ("help" "halp")
    (reply irc args "Currently, I support: .votes, .vote [item], .rmvote, .count, .whodunnit [item], .whosvoted, .whathaveyoudone [nick]")
    ()))

;;; Callback and start

; Message posted callback
(defn callback [irc args]
  ; Debugging
  (println (str "<" (:nick args) "> " (:text args)))

  ; Grab sender and tokens
  (let [sender (string/lower-case (:nick args))
        tokens (vec (string/split (string/lower-case (:text args)) #" "))]

    ; If I am the subject, find response
    ; Otherwise, determine whether it is master or not and obey
    (if (= (first tokens) (string/lower-case (str nick ":")))
      (respond irc args (rest tokens))
      (if (= sender (string/lower-case master))
        (obey-master irc args tokens)
        (obey-user irc args tokens sender)))))

; Main method
(defn start []
  ; Connnect to IRC
  (def connection (connect host port nick :callbacks {:privmsg callback}))
  (identify connection pass)
  (doseq [channel channels]
    (join connection channel)
    (println "Joined " channel)))
