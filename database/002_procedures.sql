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
CREATE OR ALTER PROCEDURE dbo.usp_RequireActor @ActorId int, @AdminOnly bit = 0 AS
BEGIN
    SET NOCOUNT ON;
    IF NOT EXISTS (SELECT 1 FROM dbo.AppUsers WHERE Id=@ActorId AND Active=1 AND Role IN ('ADMIN','CASHIER','CUSTOMER')
       AND (@AdminOnly=0 OR Role='ADMIN'))
        THROW 51001, N'账号不可用或没有操作权限。', 1;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_AcquireLock @Resource nvarchar(255) AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @r int;
    EXEC @r=sys.sp_getapplock @Resource=@Resource, @LockMode='Exclusive', @LockOwner='Transaction', @LockTimeout=10000;
    IF @r < 0 THROW 51002, N'其他窗口正在处理相关业务，请稍后重试。', 1;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_SaveMovie
    @ActorId int, @Id int=NULL, @Title nvarchar(100), @DurationMinutes int, @Active bit
AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId, 1;
    IF LEN(LTRIM(RTRIM(ISNULL(@Title,N''))))=0 OR @DurationMinutes IS NULL OR @DurationMinutes NOT BETWEEN 1 AND 600 OR @Active IS NULL
        THROW 51003,N'请输入影片名称、1 至 600 分钟的片长和有效状态。',1;
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC dbo.usp_AcquireLock N'cinema-config';
        IF @Id IS NULL
            INSERT dbo.Movies(Title,DurationMinutes,Active) VALUES(LTRIM(RTRIM(@Title)),@DurationMinutes,@Active);
        ELSE BEGIN
            IF NOT EXISTS(SELECT 1 FROM dbo.Movies WHERE Id=@Id) THROW 51004,N'影片不存在。',1;
            IF @Active=0 AND EXISTS(SELECT 1 FROM dbo.Shows WHERE MovieId=@Id AND StartTime>SYSDATETIME())
                THROW 51005,N'影片仍有未开场场次，请先处理场次再停用影片。',1;
            -- 已排片场次的 EndTime 是排片时快照，不随片长修改而漂移。
            UPDATE dbo.Movies SET Title=LTRIM(RTRIM(@Title)),DurationMinutes=@DurationMinutes,Active=@Active WHERE Id=@Id;
        END;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_DeleteMovie @ActorId int,@Id int AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,1;
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC dbo.usp_AcquireLock N'cinema-config';
        IF EXISTS(SELECT 1 FROM dbo.Shows WHERE MovieId=@Id) THROW 51006,N'影片已有排片记录，不能删除。',1;
        DELETE dbo.Movies WHERE Id=@Id;
        IF @@ROWCOUNT=0 THROW 51004,N'影片不存在。',1;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_SaveHall
    @ActorId int,@Id int=NULL,@Name nvarchar(50),@RowCount int,@ColCount int
AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,1;
    IF LEN(LTRIM(RTRIM(ISNULL(@Name,N''))))=0 OR @RowCount IS NULL OR @ColCount IS NULL OR @RowCount NOT BETWEEN 1 AND 20 OR @ColCount NOT BETWEEN 1 AND 30
        THROW 51007,N'请输入影厅名称，行数范围 1 至 20，列数范围 1 至 30。',1;
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC dbo.usp_AcquireLock N'cinema-config';
        DECLARE @Generate bit=0;
        IF EXISTS(SELECT 1 FROM dbo.Halls WHERE Name=LTRIM(RTRIM(@Name)) AND (@Id IS NULL OR Id<>@Id))
            THROW 51008,N'影厅名称已经存在。',1;
        IF @Id IS NULL BEGIN
            INSERT dbo.Halls(Name,[RowCount],ColCount) VALUES(LTRIM(RTRIM(@Name)),@RowCount,@ColCount);
            SET @Id=CONVERT(int,SCOPE_IDENTITY()); SET @Generate=1;
        END ELSE BEGIN
            IF NOT EXISTS(SELECT 1 FROM dbo.Halls WHERE Id=@Id) THROW 51009,N'影厅不存在。',1;
            IF EXISTS(SELECT 1 FROM dbo.Halls WHERE Id=@Id AND ([RowCount]<>@RowCount OR ColCount<>@ColCount)) BEGIN
                IF EXISTS(SELECT 1 FROM dbo.Shows WHERE HallId=@Id) THROW 51010,N'影厅已有场次记录，不能修改座位布局。',1;
                DELETE dbo.Seats WHERE HallId=@Id; SET @Generate=1;
            END;
            UPDATE dbo.Halls SET Name=LTRIM(RTRIM(@Name)),[RowCount]=@RowCount,ColCount=@ColCount WHERE Id=@Id;
        END;
        IF @Generate=1 BEGIN
            ;WITH R AS (SELECT TOP (@RowCount) ROW_NUMBER() OVER(ORDER BY object_id) AS N FROM sys.all_objects),
                  C AS (SELECT TOP (@ColCount) ROW_NUMBER() OVER(ORDER BY object_id) AS N FROM sys.all_objects)
            INSERT dbo.Seats(HallId,RowNo,ColNo,Active) SELECT @Id,R.N,C.N,1 FROM R CROSS JOIN C;
        END;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_DeleteHall @ActorId int,@Id int AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,1;
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC dbo.usp_AcquireLock N'cinema-config';
        IF EXISTS(SELECT 1 FROM dbo.Shows WHERE HallId=@Id) THROW 51010,N'影厅已有场次记录，不能删除。',1;
        DELETE dbo.Seats WHERE HallId=@Id;
        DELETE dbo.Halls WHERE Id=@Id;
        IF @@ROWCOUNT=0 THROW 51009,N'影厅不存在。',1;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_SetSeatActive @ActorId int,@Id int,@Active bit AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,1;
    IF @Active IS NULL THROW 51011,N'请选择有效座位状态。',1;
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC dbo.usp_AcquireLock N'cinema-config';
        DECLARE @HallId int=(SELECT HallId FROM dbo.Seats WHERE Id=@Id);
        IF @HallId IS NULL THROW 51012,N'座位不存在。',1;
        IF EXISTS(SELECT 1 FROM dbo.Shows WHERE HallId=@HallId)
            THROW 51013,N'影厅已有场次记录，不能变更座位可用性，以保证历史容量一致。',1;
        UPDATE dbo.Seats SET Active=@Active WHERE Id=@Id;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_SaveShow
    @ActorId int,@Id int=NULL,@MovieId int,@HallId int,@StartTime datetime2(0),@Price decimal(10,2),@CleanupMinutes int
AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,1;
    IF @StartTime IS NULL OR @StartTime<=SYSDATETIME() THROW 51014,N'只能安排未来场次。',1;
    IF @Price IS NULL OR @Price<=0 OR @Price>99999 OR @CleanupMinutes IS NULL OR @CleanupMinutes NOT BETWEEN 0 AND 120
        THROW 51015,N'票价须大于 0 且不超过 99999，散场间隔须为 0 至 120 分钟。',1;
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC dbo.usp_AcquireLock N'cinema-config';
        DECLARE @resource nvarchar(255),@EndTime datetime2(0),@Duration int;
        IF @Id IS NOT NULL BEGIN
            SET @resource=CONCAT(N'show-',@Id);
            EXEC dbo.usp_AcquireLock @resource;
            IF NOT EXISTS(SELECT 1 FROM dbo.Shows WHERE Id=@Id) THROW 51016,N'场次不存在。',1;
            IF EXISTS(SELECT 1 FROM dbo.Shows WHERE Id=@Id AND StartTime<=SYSDATETIME())
                THROW 51017,N'已开场场次不能修改。',1;
            IF EXISTS(SELECT 1 FROM dbo.Orders WHERE ShowId=@Id) THROW 51018,N'场次已有订单记录，不能修改。',1;
        END;
        SET @resource=CONCAT(N'hall-',@HallId);
        EXEC dbo.usp_AcquireLock @resource;
        SELECT @Duration=DurationMinutes FROM dbo.Movies WHERE Id=@MovieId AND Active=1;
        IF @Duration IS NULL THROW 51019,N'影片不存在或已经停用。',1;
        IF NOT EXISTS(SELECT 1 FROM dbo.Halls WHERE Id=@HallId) THROW 51009,N'影厅不存在。',1;
        IF NOT EXISTS(SELECT 1 FROM dbo.Seats WHERE HallId=@HallId AND Active=1)
            THROW 51020,N'影厅没有可用座位。',1;
        SET @EndTime=DATEADD(minute,@Duration,@StartTime);
        IF @StartTime<=SYSDATETIME() THROW 51014,N'只能安排未来场次。',1;
        IF EXISTS(SELECT 1 FROM dbo.Shows WHERE HallId=@HallId AND (@Id IS NULL OR Id<>@Id)
            AND StartTime<DATEADD(minute,@CleanupMinutes,@EndTime)
            AND DATEADD(minute,CleanupMinutes,EndTime)>@StartTime)
            THROW 51021,N'排片与已有场次冲突，请同时预留散场间隔。',1;
        IF @Id IS NULL
            INSERT dbo.Shows(MovieId,HallId,StartTime,EndTime,Price,CleanupMinutes)
            VALUES(@MovieId,@HallId,@StartTime,@EndTime,@Price,@CleanupMinutes);
        ELSE
            UPDATE dbo.Shows SET MovieId=@MovieId,HallId=@HallId,StartTime=@StartTime,EndTime=@EndTime,Price=@Price,CleanupMinutes=@CleanupMinutes WHERE Id=@Id;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_DeleteShow @ActorId int,@Id int AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,1;
    BEGIN TRY
        BEGIN TRANSACTION;
        EXEC dbo.usp_AcquireLock N'cinema-config';
        DECLARE @resource nvarchar(255)=CONCAT(N'show-',@Id);
        EXEC dbo.usp_AcquireLock @resource;
        IF NOT EXISTS(SELECT 1 FROM dbo.Shows WHERE Id=@Id) THROW 51016,N'场次不存在。',1;
        IF EXISTS(SELECT 1 FROM dbo.Shows WHERE Id=@Id AND StartTime<=SYSDATETIME())
            THROW 51017,N'已开场场次不能删除。',1;
        IF EXISTS(SELECT 1 FROM dbo.Orders WHERE ShowId=@Id) THROW 51018,N'场次已有订单记录，不能删除。',1;
        DELETE dbo.Shows WHERE Id=@Id;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_SellTickets
    @ActorId int,@ShowId int,@SeatIds nvarchar(max)
AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,0;
    DECLARE @Requested TABLE(SeatId int);
    IF @SeatIds IS NULL OR LEN(@SeatIds)=0 OR @SeatIds LIKE N'%,,%' OR LEFT(@SeatIds,1)=N',' OR RIGHT(@SeatIds,1)=N','
        THROW 51022,N'请选择有效座位。',1;
    INSERT @Requested(SeatId) SELECT TRY_CONVERT(int,NULLIF(LTRIM(RTRIM(value)),N'')) FROM STRING_SPLIT(@SeatIds,N',');
    IF NOT EXISTS(SELECT 1 FROM @Requested) OR EXISTS(SELECT 1 FROM @Requested WHERE SeatId IS NULL OR SeatId<=0)
        THROW 51022,N'座位编号无效。',1;
    IF EXISTS(SELECT SeatId FROM @Requested GROUP BY SeatId HAVING COUNT(*)>1)
        THROW 51023,N'同一订单不能重复选择同一座位。',1;
    IF (SELECT COUNT(*) FROM @Requested)>50 THROW 51024,N'每笔订单最多选择 50 个座位。',1;
    BEGIN TRY
        BEGIN TRANSACTION;
        DECLARE @resource nvarchar(255)=CONCAT(N'show-',@ShowId);
        EXEC dbo.usp_AcquireLock @resource;
        DECLARE @HallId int,@Price decimal(10,2),@StartTime datetime2(0),@OrderId bigint;
        SELECT @HallId=HallId,@Price=Price,@StartTime=StartTime FROM dbo.Shows WITH(UPDLOCK,HOLDLOCK) WHERE Id=@ShowId;
        IF @HallId IS NULL THROW 51016,N'场次不存在。',1;
        IF @StartTime<=SYSDATETIME() THROW 51025,N'场次已开场，不能售票。',1;
        IF EXISTS(SELECT 1 FROM @Requested r LEFT JOIN dbo.Seats s ON s.Id=r.SeatId AND s.HallId=@HallId AND s.Active=1 WHERE s.Id IS NULL)
            THROW 51026,N'所选座位不存在、已停用或不属于本场次影厅。',1;
        IF EXISTS(SELECT 1 FROM dbo.Tickets t JOIN @Requested r ON r.SeatId=t.SeatId WHERE t.ShowId=@ShowId AND t.Active=1)
            THROW 51027,N'所选座位已被售出，请刷新座位图。',1;
        INSERT dbo.Orders(ShowId,UserId,TotalAmount,Status,UpdatedBy)
            SELECT @ShowId,@ActorId,@Price*COUNT(*),'PAID',@ActorId FROM @Requested;
        SET @OrderId=CONVERT(bigint,SCOPE_IDENTITY());
        INSERT dbo.Tickets(OrderId,ShowId,SeatId,HallId,Price,Active)
            SELECT @OrderId,@ShowId,SeatId,@HallId,@Price,1 FROM @Requested;
        COMMIT;
        SELECT @OrderId AS OrderId;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_RefundOrder @ActorId int,@OrderId bigint AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    EXEC dbo.usp_RequireActor @ActorId,0;
    BEGIN TRY
        BEGIN TRANSACTION;
        -- Staff may process counter orders; customers may only refund their own purchase.
        DECLARE @ShowId int=(SELECT o.ShowId FROM dbo.Orders o
            JOIN dbo.AppUsers u ON u.Id=@ActorId AND u.Active=1
            WHERE o.Id=@OrderId AND (u.Role IN ('ADMIN','CASHIER') OR o.UserId=@ActorId));
        IF @ShowId IS NULL THROW 51031,N'订单不存在或无权退票。',1;
        DECLARE @resource nvarchar(255)=CONCAT(N'show-',@ShowId);
        EXEC dbo.usp_AcquireLock @resource;
        DECLARE @Status varchar(10),@StartTime datetime2(0);
        SELECT @Status=Status FROM dbo.Orders WITH(UPDLOCK,HOLDLOCK) WHERE Id=@OrderId;
        IF @Status<>'PAID' THROW 51029,N'订单已经退票，不能重复退票。',1;
        SELECT @StartTime=StartTime FROM dbo.Shows WHERE Id=@ShowId;
        IF @StartTime<=SYSDATETIME() THROW 51030,N'场次已开场，不能退票。',1;
        UPDATE dbo.Tickets SET Active=0 WHERE OrderId=@OrderId AND Active=1;
        UPDATE dbo.Orders SET Status='REFUNDED',RefundedAt=SYSDATETIME(),UpdatedBy=@ActorId WHERE Id=@OrderId;
        COMMIT;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        THROW;
    END CATCH;
END;
GO
CREATE OR ALTER PROCEDURE dbo.usp_RegisterCustomer
    @Username nvarchar(4000), @DisplayName nvarchar(4000), @PasswordHash nvarchar(4000)
AS
BEGIN
    SET NOCOUNT ON; SET XACT_ABORT ON;
    SET @Username=LTRIM(RTRIM(@Username));
    SET @DisplayName=LTRIM(RTRIM(@DisplayName));
    IF @Username IS NULL OR LEN(@Username) NOT BETWEEN 3 AND 30
       OR @Username COLLATE Latin1_General_100_BIN2 LIKE N'%[^A-Za-z0-9_]%'
       OR @DisplayName IS NULL OR LEN(@DisplayName) NOT BETWEEN 1 AND 50
       OR @PasswordHash IS NULL OR LEN(@PasswordHash) NOT BETWEEN 70 AND 256
       OR @PasswordHash NOT LIKE N'pbkdf2:210000:%'
        THROW 51101,N'注册信息无效，请检查账号、昵称和密码。',1;
    BEGIN TRY
        BEGIN TRANSACTION;
        DECLARE @resource nvarchar(255)=N'register-'+UPPER(@Username),@UserId int;
        EXEC dbo.usp_AcquireLock @resource;
        IF EXISTS(SELECT 1 FROM dbo.AppUsers WITH(UPDLOCK,HOLDLOCK) WHERE Username=@Username)
            THROW 51102,N'账号已存在，请换一个账号或直接登录。',1;
        -- No role input is accepted: self-service registration cannot create staff accounts.
        INSERT dbo.AppUsers(Username,PasswordHash,DisplayName,Role,Active)
            VALUES(@Username,@PasswordHash,@DisplayName,'CUSTOMER',1);
        SET @UserId=CONVERT(int,SCOPE_IDENTITY());
        COMMIT;
        SELECT @UserId AS UserId;
    END TRY BEGIN CATCH
        IF @@TRANCOUNT>0 ROLLBACK;
        IF ERROR_NUMBER() IN (2601,2627) THROW 51102,N'账号已存在，请换一个账号或直接登录。',1;
        THROW;
    END CATCH;
END;
GO
