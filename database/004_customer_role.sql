-- Non-destructive upgrade: keep all users, orders, tickets and logs.
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
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    DECLARE @RoleCheck sysname, @sql nvarchar(max);
    DECLARE checks CURSOR LOCAL FAST_FORWARD FOR
        SELECT name FROM sys.check_constraints
        WHERE parent_object_id=OBJECT_ID(N'dbo.AppUsers')
          AND (parent_column_id=COLUMNPROPERTY(OBJECT_ID(N'dbo.AppUsers'),N'Role','ColumnId')
               OR name=N'CK_AppUsers_Role');
    OPEN checks;
    FETCH NEXT FROM checks INTO @RoleCheck;
    WHILE @@FETCH_STATUS=0 BEGIN
        SET @sql=N'ALTER TABLE dbo.AppUsers DROP CONSTRAINT '+QUOTENAME(@RoleCheck);
        EXEC sys.sp_executesql @sql;
        FETCH NEXT FROM checks INTO @RoleCheck;
    END;
    CLOSE checks;
    DEALLOCATE checks;
    ALTER TABLE dbo.AppUsers WITH CHECK ADD CONSTRAINT CK_AppUsers_Role
        CHECK (Role IN ('ADMIN','CASHIER','CUSTOMER'));
    IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.Orders') AND name=N'IX_Orders_UserCreated')
        CREATE INDEX IX_Orders_UserCreated ON dbo.Orders(UserId,CreatedAt) INCLUDE(ShowId,Status,TotalAmount);
    COMMIT;
END TRY BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK;
    THROW;
END CATCH;
GO
