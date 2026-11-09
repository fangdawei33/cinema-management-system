SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;
GO
USE CinemaCourseDB;
GO
SET NOCOUNT ON;
SET XACT_ABORT ON;
IF OBJECT_ID(N'dbo.AppUsers', N'U') IS NOT NULL
    THROW 51000, N'数据库已初始化；为保护已有数据，请勿重复运行建表脚本。', 1;
BEGIN TRANSACTION;
CREATE TABLE dbo.AppUsers (
    Id int IDENTITY PRIMARY KEY,
    Username nvarchar(50) NOT NULL UNIQUE,
    PasswordHash nvarchar(256) NOT NULL,
    DisplayName nvarchar(50) NOT NULL,
    Role varchar(10) NOT NULL CONSTRAINT CK_AppUsers_Role CHECK (Role IN ('ADMIN','CASHIER','CUSTOMER')),
    Active bit NOT NULL CONSTRAINT DF_AppUsers_Active DEFAULT 1
);
CREATE TABLE dbo.Movies (
    Id int IDENTITY PRIMARY KEY,
    Title nvarchar(100) NOT NULL CHECK (LEN(LTRIM(RTRIM(Title))) > 0),
    DurationMinutes int NOT NULL CHECK (DurationMinutes BETWEEN 1 AND 600),
    Active bit NOT NULL CONSTRAINT DF_Movies_Active DEFAULT 1
);
CREATE TABLE dbo.Halls (
    Id int IDENTITY PRIMARY KEY,
    Name nvarchar(50) NOT NULL UNIQUE CHECK (LEN(LTRIM(RTRIM(Name))) > 0),
    [RowCount] int NOT NULL CHECK ([RowCount] BETWEEN 1 AND 20),
    ColCount int NOT NULL CHECK (ColCount BETWEEN 1 AND 30)
);
CREATE TABLE dbo.Seats (
    Id int IDENTITY PRIMARY KEY,
    HallId int NOT NULL REFERENCES dbo.Halls(Id),
    RowNo int NOT NULL CHECK (RowNo BETWEEN 1 AND 20),
    ColNo int NOT NULL CHECK (ColNo BETWEEN 1 AND 30),
    Active bit NOT NULL CONSTRAINT DF_Seats_Active DEFAULT 1,
    CONSTRAINT UQ_Seats_Position UNIQUE(HallId, RowNo, ColNo),
    CONSTRAINT UQ_Seats_Hall UNIQUE(Id, HallId)
);
CREATE TABLE dbo.Shows (
    Id int IDENTITY PRIMARY KEY,
    MovieId int NOT NULL REFERENCES dbo.Movies(Id),
    HallId int NOT NULL REFERENCES dbo.Halls(Id),
    StartTime datetime2(0) NOT NULL,
    EndTime datetime2(0) NOT NULL,
    Price decimal(10,2) NOT NULL CHECK (Price > 0 AND Price <= 99999),
    CleanupMinutes int NOT NULL CHECK (CleanupMinutes BETWEEN 0 AND 120),
    CONSTRAINT CK_Shows_Time CHECK (EndTime > StartTime),
    CONSTRAINT UQ_Shows_Hall UNIQUE(Id, HallId)
);
CREATE INDEX IX_Shows_HallTime ON dbo.Shows(HallId, StartTime) INCLUDE(EndTime, CleanupMinutes);
CREATE INDEX IX_Shows_MovieTime ON dbo.Shows(MovieId, StartTime);
CREATE TABLE dbo.Orders (
    Id bigint IDENTITY PRIMARY KEY,
    ShowId int NOT NULL REFERENCES dbo.Shows(Id),
    UserId int NOT NULL REFERENCES dbo.AppUsers(Id),
    TotalAmount decimal(12,2) NOT NULL CHECK (TotalAmount > 0),
    Status varchar(10) NOT NULL CHECK (Status IN ('PAID','REFUNDED')),
    CreatedAt datetime2(0) NOT NULL CONSTRAINT DF_Orders_Created DEFAULT SYSDATETIME(),
    RefundedAt datetime2(0) NULL,
    UpdatedBy int NOT NULL REFERENCES dbo.AppUsers(Id),
    CONSTRAINT CK_Orders_Refund CHECK ((Status = 'PAID' AND RefundedAt IS NULL) OR (Status = 'REFUNDED' AND RefundedAt IS NOT NULL)),
    CONSTRAINT UQ_Orders_Show UNIQUE(Id, ShowId)
);
CREATE INDEX IX_Orders_Show ON dbo.Orders(ShowId, Status);
CREATE INDEX IX_Orders_UserCreated ON dbo.Orders(UserId, CreatedAt) INCLUDE (ShowId, Status, TotalAmount);
CREATE TABLE dbo.Tickets (
    Id bigint IDENTITY PRIMARY KEY,
    OrderId bigint NOT NULL,
    ShowId int NOT NULL,
    SeatId int NOT NULL,
    HallId int NOT NULL,
    Price decimal(10,2) NOT NULL CHECK (Price > 0),
    Active bit NOT NULL CONSTRAINT DF_Tickets_Active DEFAULT 1,
    CONSTRAINT FK_Tickets_Order FOREIGN KEY(OrderId, ShowId) REFERENCES dbo.Orders(Id, ShowId),
    CONSTRAINT FK_Tickets_ShowHall FOREIGN KEY(ShowId, HallId) REFERENCES dbo.Shows(Id, HallId),
    CONSTRAINT FK_Tickets_SeatHall FOREIGN KEY(SeatId, HallId) REFERENCES dbo.Seats(Id, HallId),
    CONSTRAINT UQ_Tickets_OrderSeat UNIQUE(OrderId, SeatId)
);
CREATE UNIQUE INDEX UX_Tickets_ActiveSeat ON dbo.Tickets(ShowId, SeatId) WHERE Active = 1;
CREATE INDEX IX_Tickets_Order ON dbo.Tickets(OrderId);
CREATE TABLE dbo.OrderLogs (
    Id bigint IDENTITY PRIMARY KEY,
    OrderId bigint NOT NULL REFERENCES dbo.Orders(Id),
    OldStatus varchar(10) NULL,
    NewStatus varchar(10) NOT NULL,
    ChangedAt datetime2(0) NOT NULL CONSTRAINT DF_OrderLogs_Time DEFAULT SYSDATETIME(),
    ActorId int NOT NULL REFERENCES dbo.AppUsers(Id)
);
COMMIT;
GO
CREATE OR ALTER TRIGGER dbo.trg_OrderStatusLog ON dbo.Orders AFTER INSERT, UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    INSERT dbo.OrderLogs(OrderId, OldStatus, NewStatus, ActorId)
    SELECT i.Id, d.Status, i.Status, i.UpdatedBy
    FROM inserted i LEFT JOIN deleted d ON i.Id = d.Id
    WHERE d.Id IS NULL OR d.Status <> i.Status;
END;
GO
