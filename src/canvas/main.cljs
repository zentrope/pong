(ns canvas.main
  (:require-macros
   [cljs.core.async.macros :refer [go-loop]])
  (:require
   [cljs.core.async :refer [chan <! put! sliding-buffer]]))

(enable-console-print!)

;;-----------------------------------------------------------------------------
;; Constants
;;-----------------------------------------------------------------------------

(def SCALE-W 800)
(def SCALE-H 450)
(def WIN_SCORE 5)

(def PLAYER_1_COLOR "dodgerblue")
(def PLAYER_2_COLOR "peru")

(def SCORE_FONT "60px Helvetica")
(def SCORE_COLOR "slategray")

(def BALL_COLOR "lime")

(def KEYBOARD
  {27 :abort
   32 :space
   37 :paddle-1
   38 :up
   39 :paddle-2
   40 :down})

;;-----------------------------------------------------------------------------

(defn sqr
  [x]
  (* x x))

(defn dist
  [x1 y1 x2 y2]
  (js/Math.sqrt (+ (sqr (- x2 x1))
                   (sqr (- y2 y1)))))

;;-----------------------------------------------------------------------------
;; DOM
;;-----------------------------------------------------------------------------

(defn by-id
  [id]
  (.getElementById js/document id))

(defn attr!
  [el attr val]
  (aset el attr val)
  el)

(defn listen!
  [el type fn]
  (.addEventListener el type fn false))

;;-----------------------------------------------------------------------------
;; Object Definitions
;;-----------------------------------------------------------------------------

(defrecord GameBackground [])
(defrecord GameNet [])
(defrecord GameStartScreen [])
(defrecord GameOverScreen [])
(defrecord Paddle [x y width height vy color])
(defrecord Ball [x y radius vx vy fill-color oob?])
(defrecord Score [score x y font color align])

;;-----------------------------------------------------------------------------
;; Drawable Objects
;;-----------------------------------------------------------------------------

(defprotocol IDrawable
  (draw! [_ ctx]))

(extend-type GameBackground
  IDrawable
  (draw! [_ ctx]
    (.save ctx)
    (aset ctx "lineWidth" "0.5")
    (aset ctx "fillStyle" "black")
    (.fillRect ctx 0 0 SCALE-W SCALE-H)
    (aset ctx "lineWidth" "2")
    (aset ctx "strokeStyle" "#333")
    (.strokeRect ctx 0 0 SCALE-W SCALE-H)
    (.restore ctx)))

(extend-type GameNet
  IDrawable
  (draw! [- ctx]
    (.save ctx)
    (aset ctx "lineWidth" "1")
    (aset ctx "strokeStyle" SCORE_COLOR)
    (.setLineDash ctx (array 1 2))
    (.beginPath ctx)
    (.moveTo ctx (/ SCALE-W 2) 0)
    (.lineTo ctx (/ SCALE-W 2) SCALE-H)
    (.stroke ctx)
    (.closePath ctx)
    (.restore ctx)))

(extend-type GameStartScreen
  IDrawable
  (draw! [_ ctx]
    (.save ctx)
    (aset ctx "textAlign" "center")
    ;;
    (aset ctx "font" "60px Helvetica")
    (aset ctx "fillStyle" "peru")
    (.fillText ctx "Welcome to Pong!" (/ SCALE-W 2) 100)
    ;;
    (aset ctx "font" "italic 14px Helvetica")
    (aset ctx "fillStyle" "slategray")
    (.fillText ctx "single player" (/ SCALE-W 2) 150)
    ;;
    (aset ctx "font" "20px Helvetica")
    (aset ctx "fillStyle" "dodgerblue")
    (.fillText ctx "Press the spacebar to start." (/ SCALE-W 2) 300)
    ;;
    (aset ctx "font" "italic 12px Helvetica")
    (aset ctx "fillStyle" "slategray")
    (.fillText ctx "[ Right ] and [ Left ] arrows to switch paddles." (/ SCALE-W 2) 370)
    (.fillText ctx "[ Up ] and [ Down ] arrows to move paddles." (/ SCALE-W 2) 390)
    (.fillText ctx "[ Spacebar ] to pause ." (/ SCALE-W 2) 410)
    (.fillText ctx "[ Escape ] to quit." (/ SCALE-W 2) 430)
    (.restore ctx)))

(extend-type GameOverScreen
  IDrawable
  (draw! [_ ctx]
    (.save ctx)
    (aset ctx "textAlign" "center")
    (aset ctx "font" "60px Helvetica")
    (aset ctx "fillStyle" "peru")
    (.fillText ctx "GAME OVER" (/ SCALE-W 2) 200)
    (aset ctx "font" "20px Helvetica")
    (aset ctx "fillStyle" "dodgerblue")
    (.fillText ctx "Press the spacebar." (/ SCALE-W 2) 300)
    (.restore ctx)))

(extend-type Score
  IDrawable
  (draw! [{:keys [score x y font color align]} ctx]
    (.save ctx)
    (aset ctx "font" font)
    (aset ctx "fillStyle" color)
    (aset ctx "textAlign" align)
    (.fillText ctx (str score) x y)
    (.restore ctx)))

(extend-type Paddle
  IDrawable
  (draw! [{:keys [color x y width height]} ctx]
    (.save ctx)
    (aset ctx "fillStyle" color)
    (.fillRect ctx x y width height)
    (.restore ctx)))

(extend-type Ball
  IDrawable
  (draw! [{:keys [x y radius fill-color] :as ball} ctx]
    (.save ctx)
    (aset ctx "fillStyle" fill-color)
    (.beginPath ctx)
    (.arc ctx x y radius 0 (* 2 js/Math.PI) false)
    (.fill ctx)
    (.closePath ctx)
    (.restore ctx)))

;;-----------------------------------------------------------------------------
;; Moveable Objects
;;-----------------------------------------------------------------------------

(defprotocol IMovable
  (move [_]))

(extend-type Ball
  IMovable
  (move [{:keys [x y vx vy radius oob?] :as ball}]
    (when-not oob?
      (cond
        (= x radius)
        (assoc ball :oob? false :x -100)
        ;;
        (= x (- SCALE-W radius))
        (assoc ball :oob? false :x (+ SCALE-W 100))
        ;;
        :else
        (let [vy (if (< radius y (- SCALE-H radius)) vy (* -1 vy))]
          (assoc ball :x (+ x vx) :y (+ y vy) :vy vy))))))

;;-----------------------------------------------------------------------------
;; Controllable Objects
;;-----------------------------------------------------------------------------

(defprotocol IControllable
  (control! [_ event]))

(extend-type Paddle
  IControllable
  (control! [{:keys [y vy height] :as paddle} event]
    (condp = event
      :up   (if (> (- y vy) 0) (assoc paddle :y (- y vy)) paddle)
      :down (if (< (+ y vy height) SCALE-H ) (assoc paddle :y (+ y vy)) paddle)
      :else paddle)))

(defprotocol IPositionable
  (position! [_ y]))

(extend-type Paddle
  IPositionable
  (position! [{:keys [y height] :as paddle} new-y]
    (let [new-y (- new-y (/ height 2))
          new-y (cond
                  (< new-y 0) 0
                  (> new-y (- SCALE-H height)) (- SCALE-H height)
                  :else new-y)]
      (assoc paddle :y new-y))))

;;-----------------------------------------------------------------------------
;; Game State
;;-----------------------------------------------------------------------------

(def objects
  {:background (GameBackground.)
   :net        (GameNet.)
   :game-start (GameStartScreen.)
   :game-over  (GameOverScreen.)
   :score-1    (Score. 0 (- (/ SCALE-W 2) 75) 60 SCORE_FONT SCORE_COLOR "right")
   :score-2    (Score. 0 (+ (/ SCALE-W 2) 75) 60 SCORE_FONT SCORE_COLOR "left")
   :paddle-1   (Paddle. 20 (- SCALE-H 120) 20 100 10 PLAYER_1_COLOR)
   :paddle-2   (Paddle. (- SCALE-W 40) 20 20 100 10 PLAYER_2_COLOR)
   :ball       (Ball. 400 225 13 3 2 BALL_COLOR false)})

(defonce state
  (atom (merge objects
               {:mode :game-start ;; game-start game-over playing
                :pause? false
                :current-paddle :paddle-1})))

;;-----------------------------------------------------------------------------
;; Animation
;;-----------------------------------------------------------------------------

(defn draw-phase!
  [state ctx]
  ;;
  ;; Always
  (.clearRect ctx 0 0 SCALE-W SCALE-H)
  (draw! (:background @state) ctx)
  ;;
  ;; While playing and game-over
  (when (contains? #{:playing :game-over} (:mode @state))
    (draw! (:score-2 @state) ctx)
    (draw! (:score-1 @state) ctx))
  ;;
  (when (contains? #{:game-over} (:mode @state))
    (draw! (:game-over @state) ctx))
  ;;
  (when (contains? #{:game-start} (:mode @state))
    (draw! (:game-start @state) ctx))
  ;;
  ;; While playing.
  (when (contains? #{:playing} (:mode @state))
    (draw! (:net @state) ctx)
    (draw! (:paddle-1 @state) ctx)
    (draw! (:paddle-2 @state) ctx)
    (draw! (:ball @state) ctx)))

(defn move-phase!
  [{:keys [ball] :as state}]
  (merge state {:ball (move ball)}))

(defn hit?
  [ball paddle]
  (let [{x1 :x y1 :y} paddle
        x2 (+ x1 (:width paddle))
        y2 (+ y1 (:height paddle))
        {x :x y :y radius :radius} ball]
    ;; janky depending on direction of ball
    (or (and (<= y1 y y2) (<= x1 x x2))
        (and (<= y1 y y2) (or (>= radius (dist x2 y x y))
                              (>= radius (dist x1 y x y))))
        (and (<= x1 x x2) (or (>= radius (dist x y2 x y))
                              (>= radius (dist x y1 x y)))))))

(defn collision-phase!
  [{:keys [ball paddle-1 paddle-2 mode] :as state}]
  (if (or (hit? ball paddle-1)
          (hit? ball paddle-2))
    (let [{:keys [vx vy]} ball
          vxf (if (< vx 0) dec inc)
          vyf (if (< vy 0) dec inc)]
      (assoc state :ball (assoc ball :vx (* -1 (vxf vx)) :vy (vyf vy))))
    state))

(defn score-phase!
  [{:keys [ball score-1 score-2] :as state}]
  (if (<= 0 (:x ball) SCALE-W)
    state
    (let [{:keys [x vx radius]} ball
          {score1 :score} score-1
          {score2 :score} score-2
          p1? (> x SCALE-W)
          p2? (< x 0)
          score1 (if p1? (inc score1) score1)
          score2 (if p2? (inc score2) score2)
          s1 (assoc score-1 :score score1)
          s2 (assoc score-2 :score score2)
          mode (if (or (>= score1 WIN_SCORE)
                       (>= score2 WIN_SCORE))
                 :game-over
                 (:mode state))
          ball (if p2?
                 (assoc ball :x (- SCALE-W 25) :y (/ SCALE-H 2) :vy -2 :vx -3)
                 (assoc ball :x 25 :y (/ SCALE-H 2) :vy 2 :vx 3))]
      (assoc state :score-1 s1 :score-2 s2 :ball ball :mode mode))))

(defn animate-loop!
  [state ctx]
  (when-not (:pause? @state)
    (when (= (:mode @state) :playing)
      (swap! state #(-> % move-phase! collision-phase! score-phase!)))
    (draw-phase! state ctx))
  ;; (set! (.-innerHTML (by-id "debug"))
  ;;       (apply str (for [[k v] @state]
  ;;                    (str k ": " (pr-str v) "<br/>"))))
  (.requestAnimationFrame js/window (partial animate-loop! state ctx)))

;;-----------------------------------------------------------------------------
;; Control
;;-----------------------------------------------------------------------------

(defn resize!
  [state ctx]
  (let [w (- (.-innerWidth js/window) 40)
        h (- (int (/ (* w 9) 16)) 40)]
    (-> (by-id "canvas")
        (attr! "width" w)
        (attr! "height" h))
    (.scale ctx (/ w SCALE-W) (/ h SCALE-H))
    (draw-phase! state ctx)))

(defn- event-loop!
  [state ch]
  (go-loop []
    (when-let [event (<! ch)]
      (cond
        (contains? #{:up :down} event)
        (when-not (:pause? @state)
          (swap! state (fn [{id :current-paddle :as state}]
                         (assoc state id (control! (id state) event)))))

        (contains? #{:paddle-1 :paddle-2} event)
        (when-not (:pause? @state)
          (swap! state assoc :current-paddle event))

        (= :abort event)
        (swap! state #(if (:pause? %)
                        (assoc % :pause? false)
                        (assoc % :mode :game-start)))

        (= :space event)
        (swap! state #(case (:mode %)
                        :game-start (merge % objects {:mode :playing})
                        :game-over (assoc % :mode :game-start)
                        (assoc % :pause? (not (:pause? %)))))

        :else
        (println "Unhandled event:" event))
      (recur))))

(def control-stream
  (comp (map #(or (get KEYBOARD %) :unknown))
        (filter #(not= % :unknown))))

(defn paddle-move!
  [state e]
  (when (and (= (:mode @state) :playing)
             (not (nil? (:mouse-status @state))))
    (let [t (.-target e)
          w (- (.-innerWidth js/window) 40)
          h (- (int (/ (* w 9) 16)) 40)
          sh (/ h SCALE-H)
          new-y (int (/ (- (.-clientY e) (.-offsetTop t)) sh))
          paddle (:mouse-status @state)
          object (get @state paddle)]
      (swap! state assoc paddle (position! object new-y)))))

(defn paddle-grab!
  [state e]
  (let [x (.-clientX e)
        ww (/ (.-innerWidth js/window) 2)
        status (if (>= x ww) :paddle-2 :paddle-1)]
    (swap! state assoc :mouse-status status)))

(defn paddle-release!
  [state e]
  (swap! state assoc :mouse-status nil))

(defn- main
  []
  (println "Welcome to Pong Single Player")
  (let [canvas (by-id "canvas")
        ctx (.getContext canvas "2d")
        events (chan (sliding-buffer 10) control-stream)]
    (event-loop! state events)
    (listen! js/window   "resize"  #(resize! state ctx))
    (listen! js/document "keydown" #(put! events (.-keyCode %)))
    (listen! canvas      "mousemove" #(paddle-move! state %))
    (listen! canvas      "mousedown" #(paddle-grab! state %))
    (listen! canvas      "mouseup" #(paddle-release! state %))
    (resize! state ctx)
    (animate-loop! state ctx)))

(set! (.-onload js/window) main)
