# Local course provisioning. Called between timed attempts, exclusively inside the generated practice save.
gamerule doMobSpawning false
gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doImmediateRespawn true
gamerule doTileDrops true
gamerule sendCommandFeedback false
time set 6000
weather clear
difficulty peaceful
kill @e[type=minecraft:item,x=0,y=65,z=3,distance=..28]
tp @e[tag=asa_course_villager] 32 65 32
kill @e[tag=asa_course_villager]
fill -5 64 -3 5 64 19 minecraft:smooth_stone
fill -5 65 -3 5 69 19 minecraft:air
fill -1 64 -2 1 64 18 minecraft:polished_andesite
fill -1 64 8 1 64 8 minecraft:yellow_concrete
fill -1 64 16 1 64 16 minecraft:lime_concrete
fill -4 65 1 -3 67 5 minecraft:oak_planks
fill -2 65 1 -2 67 5 minecraft:oak_planks
fill -2 65 2 -2 66 3 minecraft:air
fill -4 68 1 -2 68 5 minecraft:spruce_planks
setblock 0 65 3 minecraft:red_bed[facing=south,part=foot]
setblock 0 65 4 minecraft:red_bed[facing=south,part=head]
setblock 2 65 2 minecraft:oak_log
setworldspawn 0 65 0
gamemode survival @a
clear @a
effect clear @a
tp @a 0.5 65 0.5 0 22
spawnpoint @a 0 65 0
tag @a add asa_ready
execute if score #villagers asra_course matches 1 run summon minecraft:villager -1.55 65 2.55 {Tags:["asa_course_villager"],PersistenceRequired:1b}
kill @e[type=minecraft:item,x=0,y=65,z=3,distance=..28]
scoreboard players add #generation asra_course 1
