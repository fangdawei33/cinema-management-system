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
-- 演示账号 admin / cashier，初始密码 Cinema@2026，仅供本地课设演示。
-- PBKDF2-HMAC-SHA256，210000 次，格式 pbkdf2:次数:Base64盐:Base64派生值。
IF EXISTS(SELECT 1 FROM dbo.AppUsers)
    THROW 51100,N'已有账号数据，拒绝重复写入演示数据。',1;
BEGIN TRY
    BEGIN TRANSACTION;
    INSERT dbo.AppUsers(Username,PasswordHash,DisplayName,Role,Active) VALUES
    (N'admin',N'pbkdf2:210000:Y2luZW1hLWNvdXJzZS1kZW1vLXYx:mFABWtIaWjerrXQasXoK9/GpfLJIeIqzEEg6BWjjTC4=',N'影院管理员','ADMIN',1),
    (N'cashier',N'pbkdf2:210000:Y2luZW1hLWNvdXJzZS1kZW1vLXYx:mFABWtIaWjerrXQasXoK9/GpfLJIeIqzEEg6BWjjTC4=',N'演示售票员','CASHIER',1);
    DECLARE @admin int=(SELECT Id FROM dbo.AppUsers WHERE Username=N'admin');
    EXEC dbo.usp_SaveMovie @admin,NULL,N'星海远航',120,1;
    EXEC dbo.usp_SaveMovie @admin,NULL,N'山城来信',105,1;
    EXEC dbo.usp_SaveMovie @admin,NULL,N'夏日回声',95,1;
    EXEC dbo.usp_SaveHall @admin,NULL,N'一号激光厅',8,10;
    EXEC dbo.usp_SaveHall @admin,NULL,N'二号舒享厅',6,8;
    DECLARE @hall1 int=(SELECT Id FROM dbo.Halls WHERE Name=N'一号激光厅'),
            @hall2 int=(SELECT Id FROM dbo.Halls WHERE Name=N'二号舒享厅'),
            @movie1 int=(SELECT Id FROM dbo.Movies WHERE Title=N'星海远航'),
            @movie2 int=(SELECT Id FROM dbo.Movies WHERE Title=N'山城来信'),
            @movie3 int=(SELECT Id FROM dbo.Movies WHERE Title=N'夏日回声'),
            @tomorrow datetime2(0)=DATEADD(day,1,CONVERT(datetime2(0),CONVERT(date,SYSDATETIME()))),
            @time datetime2(0);
    SET @time=DATEADD(hour,10,@tomorrow);
    EXEC dbo.usp_SaveShow @admin,NULL,@movie1,@hall1,@time,45,15;
    EXEC dbo.usp_SaveShow @admin,NULL,@movie2,@hall2,@time,38,15;
    SET @time=DATEADD(hour,14,@tomorrow);
    EXEC dbo.usp_SaveShow @admin,NULL,@movie2,@hall1,@time,40,15;
    EXEC dbo.usp_SaveShow @admin,NULL,@movie3,@hall2,@time,35,15;
    SET @time=DATEADD(hour,18,@tomorrow);
    EXEC dbo.usp_SaveShow @admin,NULL,@movie3,@hall1,@time,42,15;
    EXEC dbo.usp_SaveShow @admin,NULL,@movie1,@hall2,@time,48,15;
    COMMIT;
END TRY BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK;
    THROW;
END CATCH;
GO
