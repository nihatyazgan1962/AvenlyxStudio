#include "mainwindow.h"

#include <QApplication>

int main(int argc, char* argv[]) {
    QApplication app(argc, argv);
    app.setStyle("Fusion");
    app.setApplicationName("Avenlyx Studio");
    app.setApplicationDisplayName("Avenlyx Studio");
    app.setApplicationVersion("2.0.0");
    app.setOrganizationName("Avenlyx");

    MainWindow w;
    w.show();

    return app.exec();
}
