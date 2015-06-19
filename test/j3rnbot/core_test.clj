(ns j3rnbot.core-test
  (:use [korma.core :exclude (join)])
  (:require [clojure.test :refer :all]
            [j3rnbot.core :refer :all]))

(defn reply-redefined [f]
  (with-redefs-fn {#'irclj.core/reply (fn [irc args msg] msg)}
    #(f)))

(defn create-users []
  (insert users (korma.core/values {:nick "TestUser1"}))
  (insert users (korma.core/values {:nick "TestUser2"})))

(defn delete-users []
  (delete users))

(defn create-votes []
  (insert
    votes
    (values [{:users_id (get (first (korma.core/select users)) :id)
                         :item "pepperoni"}
                        {:users_id (get (last (select users)) :id)
                         :item "cheese"}])))

(defn delete-votes []
  (delete votes))

(use-fixtures :each reply-redefined)

(deftest vote-string-test
  (testing "special reply for no votes"
    (is (= (vote-string nil nil) "No votes")))
  (testing "standard reply for several votes"
    (do
      (create-users)
      (create-votes)
      (is (= (vote-string nil nil) "pepperoni: 1 cheese: 1 "))
      (delete-votes)
      (delete-users))))

(deftest count-votes-test
  (testing "zero votes"
    (is (= "0 votes" (count-votes nil nil))))
  (testing "two votes"
    (do
      (create-users)
      (create-votes)
      (is (= "2 votes") (count-votes nil nil))
      (delete-votes)
      (delete-users))))

(deftest whodunnit-test
  (testing "says user that voted for item"
    (do
      (create-users)
      (create-votes)
      (is (= "TestUser1 voted for that item") (whodunnit nil nil "pepperoni"))
      (delete-votes)
      (delete-users)))
  (testing "says Nobody for non-voted on item"
    (is (= "Nobody voted for that item") (whodunnit nil nil "anchovy"))))

(deftest whos-voted-test
  (testing "lists users who have voted"
    (do
      (create-users)
      (create-votes)
      (is (= "TestUser1, TestUser2") (whos-voted nil nil))
      (delete-votes)
      (delete-users)))
  (testing "says Nobody for no votes"
    (is (= "Nobody" (whos-voted nil nil)))))

(deftest dunnitwho-test
  (testing "says what user voted for"
    (do
      (create-users)
      (create-votes)
      (is (= "pepperoni") (dunnitwho nil nil "TestUser1"))
      (delete-votes)
      (delete-users)))
  (testing "complains when user does not exist"
    (is (= "User with nick \"TestUser\" not found" (dunnitwho nil nil "TestUser"))))
  (testing "states when user has not voted"
    (create-users)
    (is (= "TestUser1 has not voted" (dunnitwho nil nil "TestUser1")))
    (delete-users)))

(deftest clear-votes-test
  (testing "soft deletes votes"
    (create-users)
    (create-votes)
    (is (= 2 (count (select votes (where {:old false})))))
    (clear-votes! nil nil)
    (is (= 0 (count (select votes (where {:old false})))))
    (delete-votes)
    (delete-users))
  (testing "does not actually delete votes"
    (create-users)
    (create-votes)
    (clear-votes! nil nil)
    (is (= 2 (count (select votes))))
    (delete-votes)
    (delete-users))
  (testing "confirms votes cleared"
    (is (= "Votes cleared" (clear-votes! nil nil)))))

(deftest rm-vote-test
  (testing "confirms vote deleted"
    (create-users)
    (create-votes)
    (is (= "Vote for pepperoni deleted" (rm-vote! nil nil "TestUser1")))
    (delete-votes)
    (delete-users))
  (testing "actually deletes vote"
    (create-users)
    (create-votes)
    (is (= 2 (count (select votes))))
    (rm-vote! nil nil "TestUser1")
    (is (= 1 (count (select votes))))
    (delete-votes)
    (delete-users)))
