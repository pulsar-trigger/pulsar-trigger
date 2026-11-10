# Used only by the m5sticks3_pm env (framework = arduino, espidf).
FILE(GLOB_RECURSE app_sources ${CMAKE_SOURCE_DIR}/src/*.*)
idf_component_register(SRCS ${app_sources} INCLUDE_DIRS "../include")
