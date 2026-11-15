cmake_minimum_required(VERSION 3.16)
project(AvenlyxStudio VERSION 2.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTOUIC ON)
set(CMAKE_AUTORCC ON)

find_package(Qt6 REQUIRED COMPONENTS Core Gui Widgets Svg)

set(SOURCES
    src/main.cpp
    src/formats.h
    src/formats.cpp
    src/mediaprobe.h
    src/mediaprobe.cpp
    src/projectmodel.h
    src/projectmodel.cpp
    src/converterworker.h
    src/converterworker.cpp
    src/timelineexporter.h
    src/timelineexporter.cpp
    src/previewprovider.h
    src/previewprovider.cpp
    src/previewwidget.h
    src/previewwidget.cpp
    src/timelinewidget.h
    src/timelinewidget.cpp
    src/mainwindow.h
    src/mainwindow.cpp
)

if(WIN32)
    add_executable(AvenlyxStudio WIN32 ${SOURCES})
else()
    add_executable(AvenlyxStudio ${SOURCES})
endif()

target_link_libraries(AvenlyxStudio PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Widgets
    Qt6::Svg
)

if(MINGW)
    target_compile_options(AvenlyxStudio PRIVATE -Wall -Wextra)
endif()

