"""The demo library: invented artists and albums. Everything here is original; paths are ASCII, titles live in tags."""

# fmt: flac24 (24-bit/96 kHz), flac16, mp3, opus
# style: key of synth.STYLES. bpm: (lo, hi). modes: list. bpb: beats per bar. secs: (lo, hi)
ARTISTS = {
    "kestrel": dict(name="Kestrel Moss", dir="Kestrel Moss", genre="Ambient", hue=165),
    "nova": dict(name="Nova Lindqvist", dir="Nova Lindqvist", genre="Synthpop", hue=320),
    "lanterns": dict(name="The Paper Lanterns", dir="The Paper Lanterns", genre="Indie Folk", hue=38),
    "marlo": dict(name="Marlo Quartet", dir="Marlo Quartet", genre="Jazz", hue=215),
    "daria": dict(name="Дарья Северная", dir="Daria Severnaya", genre="Neoclassical", hue=250),
    "yakou": dict(name="夜行バス", dir="Yakou Bus", genre="City Pop", hue=350),
    "orla": dict(name="Orla Voss", dir="Orla Voss", genre="House", hue=285),
    "tomas": dict(name="Tomás Arriaga", dir="Tomas Arriaga", genre="Classical Guitar", hue=20),
}

ALBUMS = [
    dict(id="low-tide", artist="kestrel", title="Low Tide Atlas", dir="Low Tide Atlas (2022)", year=2022, fmt="flac24", style="ambient",
         bpm=(68, 80), modes=["lydian", "major", "dorian"], secs=(32, 40), cover="dusk",
         tracks=["Salt Flat Sunrise", "Tidepool Cartography", "Drift Notation", "Lantern Fish", "The Long Exhale", "Glass Anemone", "Harbour Lights, Slowly"]),
    dict(id="fieldnotes", artist="kestrel", title="Fieldnotes from a Slow Planet", dir="Fieldnotes from a Slow Planet (2024)", year=2024, fmt="mp3", style="ambient",
         bpm=(64, 78), modes=["major", "dorian", "minor"], secs=(44, 66), cover="aurora",
         tracks=["Moss Over Concrete", "Orbit of a Small Moon", "Quiet Weather", "Pollen Season", "Night Train to Nowhere Much", "A Map of Soft Places"]),
    dict(id="neon-orchard", artist="nova", title="Neon Orchard", dir="Neon Orchard (2021)", year=2021, fmt="mp3", style="synthwave",
         bpm=(100, 118), modes=["minor", "major", "minor"], secs=(42, 62), cover="gridsun", discs=[5, 5],
         tracks=["Chrome Daydream", "Sunset Avenue", "Pixel Rain", "Velvet Highway", "Stay Until Morning",
                 "Midnight Arcade", "Static Hearts", "Glass Skyline", "Afterglow Protocol", "Neon Orchard (Reprise)"]),
    dict(id="salt-cedar", artist="lanterns", title="Salt and Cedar", dir="Salt and Cedar (2018)", year=2018, fmt="mp3", style="folk",
         bpm=(92, 112), modes=["major", "major", "dorian"], secs=(44, 64), cover="papercut",
         tracks=["Cedar Smoke", "Little Boat", "Porchlight", "Everything Is Slowly Green", "Coffee at the Bus Stop",
                 "Letters I Never Mailed", "Hollow Oak", "Barefoot in October", "Salt and Cedar"]),
    dict(id="winter-orchard", artist="lanterns", title="Winter Orchard Sessions", dir="Winter Orchard Sessions (2020)", year=2020, fmt="opus", style="folk",
         bpm=(76, 92), modes=["minor", "dorian"], secs=(46, 64), cover="aurora2",
         tracks=["Snow on the Orchard Wall", "Woodstove", "Quiet Fields", "Last Apples", "Thaw"]),
    dict(id="blue-hour", artist="marlo", title="Blue Hour at Pier Nine", dir="Blue Hour at Pier Nine (2019)", year=2019, fmt="flac16", style="jazz",
         bpm=(112, 140), modes=["major", "major", "dorian"], secs=(44, 62), cover="blocks",
         tracks=["Pier Nine", "Harbor Bells", "A Quiet Table for Four", "Midnight Ferry", "Rain on Cedar Street", "The Long Way Home", "Brass Moon", "Blue Hour"]),
    dict(id="sunday-brushes", artist="marlo", title="Sunday Brushes", dir="Sunday Brushes (2023)", year=2023, fmt="mp3", style="jazz",
         bpm=(96, 124), modes=["major", "dorian"], secs=(42, 58), cover="brush",
         tracks=["Slow Coffee", "Sunday Brushes", "Window Seat", "Pocket Waltz", "Afternoon in B-flat", "See You Thursday"]),
    dict(id="quiet-water", artist="daria", title="Тихая вода", dir="Tikhaya Voda (2020)", year=2020, fmt="mp3", style="piano",
         bpm=(60, 74), modes=["minor", "minor", "dorian"], secs=(46, 70), cover="moon", discs=[4, 4],
         tracks=["Рассвет над рекой", "Тихая вода", "Серебряный лес", "Дорога домой", "Первый снег", "Свеча в окне", "Тёплый чай", "Колыбельная для города"]),
    dict(id="november", artist="daria", title="Письма из ноября", dir="Pisma iz Noyabrya (2023)", year=2023, fmt="opus", style="piano",
         bpm=(58, 72), modes=["minor", "minor"], secs=(46, 68), cover="leaves",
         tracks=["Письмо первое", "Листопад", "Туман над озером", "Зимний вокзал", "Тишина после снега", "Письмо последнее"]),
    dict(id="neon-highway", artist="yakou", title="ネオン・ハイウェイ", dir="Neon Highway (2021)", year=2021, fmt="mp3", style="synthwave",
         bpm=(96, 112), modes=["major", "minor", "major"], secs=(42, 60), cover="highway",
         tracks=["夜のドライブ", "ネオン・ハイウェイ", "海辺のカフェ", "真夜中のプール", "月曜日のメロディ", "ガラスの街", "最終バス", "朝焼けの約束"]),
    dict(id="after-rain", artist="yakou", title="雨上がりの駅", dir="Ameagari no Eki (2024)", year=2024, fmt="opus", style="lofi",
         bpm=(72, 84), modes=["minor", "dorian"], secs=(44, 60), cover="rain",
         tracks=["雨上がりの駅", "傘の下で", "ホームの向こう側", "窓に映る夜", "始発電車"]),
    dict(id="concrete", artist="orla", title="Concrete Garden (Deluxe)", dir="Concrete Garden (2022)", year=2022, fmt="mp3", style="house",
         bpm=(120, 126), modes=["minor", "minor", "dorian"], secs=(46, 64), cover="rings", discs=[4, 4],
         tracks=["Concrete Garden", "Low Sun Rave", "Warehouse Bloom", "Signal Flowers",
                 "After Hours Tide", "Subway Sunrise", "Paper Moon (Club Mix)", "Last Train Out"]),
    dict(id="estaciones", artist="tomas", title="Cuatro Estaciones del Sur", dir="Cuatro Estaciones del Sur (2017)", year=2017, fmt="mp3", style="waltz",
         bpm=(92, 116), modes=["minor", "major", "minor"], secs=(44, 64), bpb=3, cover="tiles",
         tracks=["Primavera en el Puerto", "Calle de Naranjos", "Siesta", "Verano Eterno", "Hojas de Otoño", "Lluvia en la Plaza", "Nochebuena", "Invierno Sereno"]),
]

# Compilation: (title, artist key, style, mode, bpm, bpb)
SAMPLER = dict(id="sampler", title="Spinwell Sampler, Vol. 1", dir="Spinwell Sampler Vol 1 (2025)", year=2025, fmt="mp3", cover="shapes",
               tracks=[("Satellite Summer", "nova", "synthwave", "major", 110, 4), ("Golden Hour Dub", "orla", "house", "minor", 122, 4),
                       ("Harvest Moon Waltz", "lanterns", "folk", "major", 104, 3), ("Late Train to Brooklyn", "marlo", "jazz", "major", 128, 4),
                       ("Seabird Interlude", "kestrel", "ambient", "lydian", 72, 4), ("Лунный свет", "daria", "piano", "minor", 66, 4),
                       ("夏の終わり", "yakou", "lofi", "dorian", 80, 4), ("Serenata de Medianoche", "tomas", "waltz", "minor", 100, 3)])

# Original lyrics (synced .lrc is written for each). key: (album id, track index starting at 1)
LYRICS = {
    ("neon-orchard", 2): ["The sun is falling slow on Sunset Avenue", "We kept the windows down and let the evening through",
                          "Your hand across the radio, a song we never knew", "Every light is turning gold for me and you",
                          "So drive a little longer, there is nowhere else to be", "The city hums its secret and it hums it just for us"],
    ("neon-orchard", 5): ["Stay until morning, stay until the sky turns blue", "I will keep the kettle warm and the night will keep for two",
                          "The stars can wait their turn tonight", "Stay until morning, I am holding on to you", "Let the dawn come find us here"],
    ("salt-cedar", 3): ["Leave the porchlight on for me", "I am walking home through the apple trees", "The moon is a lantern someone forgot to dim",
                        "Leave the porchlight on for me", "I can see it through the leaves", "Every window is a promise that you keep"],
    ("salt-cedar", 2): ["Little boat on a quiet bay", "Carry all my worries far away", "I will tie you to the dock tonight",
                        "And watch you rock in the morning light", "Little boat, little boat, take me home"],
    ("quiet-water", 2): ["Тихая вода под утренним мостом", "Сквозь туман проходит лёгкий свет", "Я иду по берегу домой",
                         "И никто не знает, как мне хорошо", "Тихая вода, не торопись", "Пусть река расскажет мне о нас"],
    ("quiet-water", 5): ["Первый снег ложится на ладони", "Город засыпает у окна", "Я храню тебя в своём молчании",
                         "Пока тает тишина", "Первый снег, останься до утра"],
    ("november", 1): ["Здравствуй, это я, пишу тебе из ноября", "Здесь пахнет мокрой листвой и дымом", "Фонари зажигаются раньше, чем вчера",
                      "А я всё думаю о тебе", "Передай привет весне"],
    ("neon-highway", 2): ["ネオンの光が窓に流れて", "夜の高速をふたりで走る", "ラジオから聞こえる懐かしいメロディ",
                          "このまま朝まで行けたらいいね", "ネオン・ハイウェイ、どこまでも"],
    ("neon-highway", 7): ["最終バスが来る前に", "さよならを言わずに手を振るよ", "窓の向こうの街の灯り", "明日もきっと晴れるから", "最終バス、ゆっくり走って"],
    ("after-rain", 1): ["雨上がりの駅に立って", "濡れた線路が光ってる", "傘をたたんで空を見上げたら", "小さな虹がひとつ", "また会える気がした"],
    ("concrete", 1): ["Concrete garden, let it grow", "Every crack a place to bloom", "Dance until the sun comes up", "Concrete garden, take me home"],
    ("estaciones", 1): ["El mar despierta en el puerto", "Las barcas cantan al sol", "Una brisa de azahar", "Me trae tu nombre otra vez", "Primavera, quédate aquí"],
    ("sampler", 1): ["Satellite summer, hold the line", "The whole world is a little blue", "We were floating, you and I", "Satellite summer, don't say goodbye"],
}

# Reviewer playlists: name -> list of (album id, track index)
PLAYLISTS = {
    "Morning Coffee": [("sunday-brushes", 1), ("salt-cedar", 5), ("estaciones", 2), ("fieldnotes", 3), ("blue-hour", 3), ("winter-orchard", 2), ("sunday-brushes", 3), ("salt-cedar", 3)],
    "Night Drive": [("neon-orchard", 1), ("neon-orchard", 6), ("neon-highway", 2), ("neon-highway", 1), ("concrete", 5), ("neon-orchard", 8), ("sampler", 1), ("neon-highway", 7), ("after-rain", 4)],
    "Focus": [("low-tide", 1), ("low-tide", 3), ("quiet-water", 2), ("fieldnotes", 1), ("november", 3), ("low-tide", 5), ("quiet-water", 7), ("fieldnotes", 6)],
    "Sunday Slow": [("quiet-water", 5), ("november", 1), ("estaciones", 6), ("after-rain", 1), ("winter-orchard", 5), ("blue-hour", 8), ("sunday-brushes", 6)],
}
STARRED = [("neon-orchard", 2), ("salt-cedar", 3), ("low-tide", 1), ("quiet-water", 2), ("neon-highway", 2), ("blue-hour", 1), ("estaciones", 1),
           ("concrete", 1), ("sunday-brushes", 2), ("november", 2)]
STARRED_ALBUMS = ["neon-orchard", "low-tide", "salt-cedar", "quiet-water"]
STARRED_ARTISTS = ["nova", "kestrel", "daria"]
